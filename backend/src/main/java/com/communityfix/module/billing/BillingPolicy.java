package com.communityfix.module.billing;

import com.communityfix.common.*;
import com.communityfix.module.notification.NotificationService;
import org.springframework.stereotype.Service;
import java.util.Map;

@Service
public class BillingPolicy {
    private final Db db;private final NotificationService notifications;
    public BillingPolicy(Db db,NotificationService notifications){this.db=db;this.notifications=notifications;}
    public boolean chargeable(Map<String,Object> order){return Db.integer(order,"billingVersion")==1&&"PRIVATE".equals(order.get("facilityScope"));}
    public Map<String,Object> accepted(long id){return db.row("SELECT * FROM repair_quote WHERE order_id=? AND status='ACCEPTED' ORDER BY revision DESC LIMIT 1",id);}
    public void requireAccepted(Map<String,Object> order){
        if(!chargeable(order))return;long id=Db.id(order,"id");
        if(db.count("SELECT COUNT(*) FROM repair_quote WHERE order_id=? AND status='PROPOSED'",id)>0||accepted(id)==null)
            throw new ApiException(409,"QUOTE_CONFIRMATION_REQUIRED","请先由居民确认维修报价，追加费用也需再次确认");
    }
    public void onConfirmed(Map<String,Object> order){
        if(!chargeable(order))return;requireAccepted(order);long id=Db.id(order,"id");var quote=accepted(id);
        long bill=db.insert("repair_bill",Db.fields("order_id",id,"quote_id",quote.get("id"),"payer_id",order.get("residentId"),"amount_fen",quote.get("amountFen")));
    }
}
