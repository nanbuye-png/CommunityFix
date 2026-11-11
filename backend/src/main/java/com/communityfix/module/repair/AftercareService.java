package com.communityfix.module.repair;

import com.communityfix.common.*;
import com.communityfix.module.notification.NotificationService;
import com.communityfix.security.CurrentUser;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

@Service
public class AftercareService {
 private final Db db;
 private final OrderService orders;
 private final IdempotencyService idempotency;
 private final NotificationService notifications;
 public AftercareService(Db db,OrderService orders,IdempotencyService idempotency,NotificationService notifications){this.db=db;this.orders=orders;this.idempotency=idempotency;this.notifications=notifications;}
 private Map<String,Object> access(long orderId){
  var order=orders.read(orderId);var user=CurrentUser.get();
  if(!(user.hasRole("RESIDENT")&&Db.id(order,"residentId")==user.id())&&!(user.hasRole("ADMIN")&&user.communities().contains(Db.id(order,"communityId"))))throw ApiException.forbidden();
  return order;
 }
 public Object list(long orderId){
  access(orderId);var rows=db.rows("SELECT * FROM repair_aftercare WHERE order_id=? ORDER BY id DESC",orderId);
  for(var row:rows)row.put("logs",db.rows("SELECT status,content,created_at FROM repair_aftercare_log WHERE aftercare_id=? ORDER BY id",row.get("id")));
  return rows;
 }
 @Transactional public Object create(long orderId,AftercareController.Create body,String key){
  var order=access(orderId);var user=CurrentUser.get();user.role("RESIDENT");
  if(Db.id(order,"residentId")!=user.id())throw ApiException.forbidden();
  return idempotency.run("aftercare:create:"+orderId,key,body,()->{
   var locked=db.required("SELECT status FROM repair_order WHERE id=? FOR UPDATE",orderId);
   if(!"CLOSED".equals(locked.get("status")))throw ApiException.bad("请在维修验收完成后申请售后");
   if(db.count("SELECT COUNT(*) FROM repair_aftercare WHERE active_order=?",orderId)>0)throw ApiException.bad("已有售后正在处理，请查看处理记录");
   long id=db.insert("repair_aftercare",Db.fields("order_id",orderId,"content",body.content().trim()));
   record(order,id,"REQUESTED",body.content().trim(),0);
   return db.required("SELECT * FROM repair_aftercare WHERE id=?",id);
  });
 }
 @Transactional public Object action(long orderId,long id,AftercareController.Action body,String key){
  var order=access(orderId);var user=CurrentUser.get();
  boolean resident=Set.of("confirm","reopen").contains(body.action());
  if(resident){user.role("RESIDENT");if(Db.id(order,"residentId")!=user.id())throw ApiException.forbidden();}
  else{user.role("ADMIN");user.community(Db.id(order,"communityId"));}
  return idempotency.run("aftercare:action:"+id,key,body,()->{
   var item=db.required("SELECT * FROM repair_aftercare WHERE id=? AND order_id=? FOR UPDATE",id,orderId);
   String expected=switch(body.action()){case "accept","reject"->"REQUESTED";case "resolve"->"PROCESSING";default->"PENDING_CONFIRM";};
   if(!expected.equals(item.get("status"))||Db.integer(item,"version")!=body.version())throw ApiException.conflict();
   String next=switch(body.action()){case "accept","reopen"->"PROCESSING";case "reject"->"REJECTED";case "resolve"->"PENDING_CONFIRM";default->"CLOSED";};
   db.execute("UPDATE repair_aftercare SET status=?,version=version+1,updated_at=UTC_TIMESTAMP(3) WHERE id=?",next,id);
   record(order,id,next,body.content().trim(),body.version()+1);
   return db.required("SELECT * FROM repair_aftercare WHERE id=?",id);
  });
 }
 private void record(Map<String,Object> order,long id,String status,String content,int version){
  db.insert("repair_aftercare_log",Db.fields("aftercare_id",id,"operator_id",CurrentUser.get().id(),"status",status,"content",content));
  String event="aftercare:"+id+":"+version;long orderId=Db.id(order,"id");
  notifications.send(Db.id(order,"residentId"),orderId,event,"售后进度更新",content);
  for(var admin:db.rows("SELECT user_id FROM admin_community WHERE community_id=?",order.get("communityId")))notifications.send(Db.id(admin,"userId"),orderId,event,"售后处理提醒",content);
 }
}
