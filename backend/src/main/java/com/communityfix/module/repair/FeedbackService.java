package com.communityfix.module.repair;

import com.communityfix.common.*;
import com.communityfix.module.notification.NotificationService;
import com.communityfix.security.CurrentUser;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Clock;
import java.util.*;

@Service
public class FeedbackService {
    private final Db db;private final OrderService orders;private final IdempotencyService idempotency;private final NotificationService notifications;private final Clock clock;
    public FeedbackService(Db db,OrderService orders,IdempotencyService idempotency,NotificationService notifications,Clock clock){this.db=db;this.orders=orders;this.idempotency=idempotency;this.notifications=notifications;this.clock=clock;}
    @Transactional public Object evaluate(long id,FeedbackController.Evaluation body,String key){return idempotency.run("evaluation:"+id,key,body,()->{var order=orders.read(id);var user=CurrentUser.get();user.role("RESIDENT");if(Db.id(order,"residentId")!=user.id())throw ApiException.forbidden();if(!"CLOSED".equals(order.get("status")))throw ApiException.conflict();long eid=db.insert("repair_evaluation",Db.fields("order_id",id,"user_id",user.id(),"worker_id",order.get("workerId"),"score",body.score(),"content",body.content()));return db.required("SELECT * FROM repair_evaluation WHERE id=?",eid);});}
    public Object evaluation(long id){orders.read(id);return db.row("SELECT * FROM repair_evaluation WHERE order_id=?",id);}
    @Transactional public Object complain(long id,FeedbackController.Complaint body,String key){return idempotency.run("complaint:"+id,key,body,()->{var order=orders.read(id);var user=CurrentUser.get();user.role("RESIDENT");if(Db.id(order,"residentId")!=user.id())throw ApiException.forbidden();if(!Set.of("IN_PROGRESS","PENDING_CONFIRM","CLOSED").contains(order.get("status")))throw ApiException.conflict();long cid=db.insert("repair_complaint",Db.fields("order_id",id,"user_id",user.id(),"content",body.content()));db.insert("repair_complaint_log",Db.fields("complaint_id",cid,"new_status","OPEN","operator_id",user.id(),"content",body.content()));for(var admin:db.rows("SELECT user_id FROM admin_community WHERE community_id=?",order.get("communityId")))notifications.send(Db.id(admin,"userId"),id,"complaint:"+cid+":0","收到报修投诉",body.content());return db.required("SELECT * FROM repair_complaint WHERE id=?",cid);});}
    public Object complaints(int page,int size){var user=CurrentUser.get();List<Object> args=new ArrayList<>();String condition;if(user.hasRole("ADMIN")){if(user.communities().isEmpty())condition="1=0";else{condition="o.community_id IN ("+String.join(",",Collections.nCopies(user.communities().size(),"?"))+")";args.addAll(user.communities());}}else{user.role("RESIDENT");condition="cp.user_id=?";args.add(user.id());}String from=" FROM repair_complaint cp JOIN repair_order o ON o.id=cp.order_id WHERE "+condition;long total=db.count("SELECT COUNT(*)"+from,args.toArray());args.add(Pages.size(size));args.add(Pages.offset(page,size));return Pages.result(db.rows("SELECT cp.*,o.order_no,o.title"+from+" ORDER BY cp.id DESC LIMIT ? OFFSET ?",args.toArray()),total,page,size);}
    @Transactional public Object process(long id,FeedbackController.Process body){var user=CurrentUser.get();user.role("ADMIN");var complaint=db.required("SELECT * FROM repair_complaint WHERE id=? FOR UPDATE",id);var order=orders.read(Db.id(complaint,"orderId"));user.community(Db.id(order,"communityId"));String old=(String)complaint.get("status");if(Db.integer(complaint,"version")!=body.version()||!("OPEN".equals(old)&&"PROCESSING".equals(body.status())||"PROCESSING".equals(old)&&"CLOSED".equals(body.status())))throw ApiException.conflict();db.execute("UPDATE repair_complaint SET status=?,resolution=?,handler_id=?,handled_at=?,version=version+1 WHERE id=? AND version=?",body.status(),body.resolution(),user.id(),clock.instant(),id,body.version());db.insert("repair_complaint_log",Db.fields("complaint_id",id,"old_status",old,"new_status",body.status(),"operator_id",user.id(),"content",body.resolution()));notifications.send(Db.id(complaint,"userId"),Db.id(complaint,"orderId"),"complaint:"+id+":"+(body.version()+1),"投诉处理进度",body.resolution());return db.required("SELECT * FROM repair_complaint WHERE id=?",id);}
    public Map<String,Object> complaint(long id){
        var complaint=db.required("SELECT * FROM repair_complaint WHERE id=?",id);
        var order=orders.read(Db.id(complaint,"orderId"));
        var user=CurrentUser.get();
        boolean manager=user.hasRole("ADMIN")&&user.communities().contains(Db.id(order,"communityId"));
        boolean owner=user.hasRole("RESIDENT")&&Db.id(complaint,"userId")==user.id();
        if(!manager&&!owner)throw ApiException.forbidden();
        var logs=db.rows("SELECT l.*,u.nickname AS operator_name FROM repair_complaint_log l LEFT JOIN sys_user u ON u.id=l.operator_id WHERE l.complaint_id=? ORDER BY l.id",id);
        return Db.fields("complaint",complaint,"order",order,"logs",logs);
    }
    public Object complaintLogs(long id){return complaint(id).get("logs");}
}
