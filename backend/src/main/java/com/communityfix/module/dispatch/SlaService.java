package com.communityfix.module.dispatch;

import com.communityfix.common.Db;
import com.communityfix.module.notification.NotificationService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.*;
import java.util.*;

@Service
public class SlaService {
    private final Db db;private final Clock clock;private final NotificationService notifications;
    public SlaService(Db db,Clock clock,NotificationService notifications){this.db=db;this.clock=clock;this.notifications=notifications;}
    public static int level(Instant start,Instant deadline,Instant now){long duration=Duration.between(start,deadline).toMillis(),elapsed=Duration.between(start,now).toMillis();if(duration<=0)return 0;double proportion=(double)elapsed/duration;return proportion>=1.5?3:proportion>=1?2:proportion>=.8?1:0;}
    @Transactional public void inspect(long id){
        var order=db.row("SELECT * FROM repair_order WHERE id=? FOR UPDATE",id);if(order==null)return;String status=(String)order.get("status"),phase;Instant start,deadline;
        if(Set.of("SUBMITTED","PENDING_DISPATCH","ASSIGNED").contains(status)){phase="RESPONSE";start=(Instant)order.get("responseStartedAt");deadline=(Instant)order.get("responseDeadlineAt");}
        else if(Set.of("ACCEPTED","IN_PROGRESS").contains(status)){phase="COMPLETION";start=(Instant)order.get("acceptedAt");deadline=(Instant)order.get("completionDeadlineAt");}
        else return;if(start==null||deadline==null)return;int level=level(start,deadline,clock.instant());if(level==0)return;
        if(db.count("SELECT COUNT(*) FROM sla_warning WHERE order_id=? AND round_no=? AND phase=? AND level=?",id,order.get("roundNo"),phase,level)>0)return;
        db.insert("sla_warning",Db.fields("order_id",id,"round_no",order.get("roundNo"),"phase",phase,"level",level,"deadline_at",deadline));
        String event="sla:"+id+":"+order.get("roundNo")+":"+phase+":"+level;
        for(var admin:db.rows("SELECT user_id FROM admin_community WHERE community_id=?",order.get("communityId")))notifications.send(Db.id(admin,"userId"),id,event,"工单时效提醒",order.get("orderNo")+" · "+(phase.equals("RESPONSE")?"响应":"维修")+" · "+(level==1?"即将超时":level==2?"已经超时":"严重超时"));
    }
}
