package com.communityfix.module.notification;

import com.communityfix.common.ApiException;
import com.communityfix.common.Db;
import com.communityfix.security.CurrentUser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

@Service
public class WechatSubscriptionService {
    private final Db db;
    private final ObjectMapper json;
    private final WechatMessageSender sender;

    public WechatSubscriptionService(Db db, ObjectMapper json, WechatMessageSender sender) {
        this.db = db;
        this.json = json;
        this.sender = sender;
    }

    public Map<String, String> configuration() {
        return Map.of("orderTemplateId", sender.acceptanceEnabled() ? sender.acceptanceTemplateId() : "", "completionTemplateId",sender.completionEnabled()?sender.completionTemplateId():"", "quoteTemplateId",sender.confirmationEnabled()?sender.confirmationTemplateId():"","paymentTemplateId",sender.paymentEnabled()?sender.paymentTemplateId():"");
    }

    @Transactional
    public void grant(String templateId) {
        if (!(sender.acceptanceEnabled() && sender.acceptanceTemplateId().equals(templateId)) && !(sender.completionEnabled()&&sender.completionTemplateId().equals(templateId)) && !(sender.confirmationEnabled()&&sender.confirmationTemplateId().equals(templateId)) && !(sender.paymentEnabled()&&sender.paymentTemplateId().equals(templateId))) {
            throw ApiException.bad("订阅消息模板尚未配置");
        }
        long userId = CurrentUser.get().id();
        var user = db.required("SELECT wechat_openid FROM sys_user WHERE id=?", userId);
        if (user.get("wechatOpenid") == null) throw ApiException.bad("请先使用微信登录");
        // 微信最终决定订阅是否有效；这里记录客户端请求成功后的单次发送意愿。
        db.execute("INSERT INTO wechat_subscription_grant(user_id,template_id,remaining_count) VALUES (?,?,1) " +
                "ON DUPLICATE KEY UPDATE remaining_count=LEAST(20,remaining_count+1),updated_at=UTC_TIMESTAMP(3)", userId, templateId);
    }

    @Transactional
    public void enqueueConfirmation(Map<String,Object> order,long quote,long amountFen){
        if(!sender.confirmationEnabled())return;
        enqueueBilling(order,"quote:"+quote,"CONFIRMATION",sender.confirmationTemplateId(),amountFen);
    }

    @Transactional
    public void enqueuePaymentDue(Map<String,Object> order,long bill,long amountFen){
        if(!sender.paymentEnabled())return;
        enqueueBilling(order,"bill:"+bill,"PAYMENT_DUE",sender.paymentTemplateId(),amountFen);
    }

    private void enqueueBilling(Map<String,Object> order,String key,String kind,String template,long amountFen){
        long user=Db.id(order,"residentId"),id=Db.id(order,"id");
        var account=db.row("SELECT wechat_openid FROM sys_user WHERE id=?",user);
        if(account==null||account.get("wechatOpenid")==null)return;
        if(db.row("SELECT id FROM notification_outbox WHERE event_key=? AND recipient_id=? AND channel='WECHAT'",key,user)!=null)return;
        var fields=Db.fields("kind",kind,"templateId",template,"orderNo",order.get("orderNo"),"amountFen",amountFen,"createdAt",localTime(order.get("createdAt")),"page","pages/detail/index?id="+id+"&billing=1");
        if("PAYMENT_DUE".equals(kind)){fields.put("content",order.get("title"));fields.put("completedAt",localTime(order.get("closedAt")));}
        String payload;try{payload=json.writeValueAsString(fields);}catch(JsonProcessingException e){throw new IllegalStateException(e);}
        if(db.execute("UPDATE wechat_subscription_grant SET remaining_count=remaining_count-1,updated_at=UTC_TIMESTAMP(3) WHERE user_id=? AND template_id=? AND remaining_count>0",user,template)==0)return;
        if(db.execute("INSERT IGNORE INTO notification_outbox(event_key,recipient_id,channel,payload) VALUES (?,?,'WECHAT',?)",key,user,payload)==0)
            db.execute("UPDATE wechat_subscription_grant SET remaining_count=LEAST(20,remaining_count+1),updated_at=UTC_TIMESTAMP(3) WHERE user_id=? AND template_id=?",user,template);
    }
    private static String localTime(Object value){return java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(java.time.ZoneId.of("Asia/Shanghai")).format((java.time.Instant)value);}

    @Transactional
    public void enqueueCompletion(Map<String,Object> order,String key){
        if(!sender.completionEnabled())return;
        long user=Db.id(order,"residentId"),id=Db.id(order,"id");String template=sender.completionTemplateId();
        var account=db.row("SELECT wechat_openid FROM sys_user WHERE id=?",user);
        if(account==null||account.get("wechatOpenid")==null)return;
        if(db.row("SELECT id FROM notification_outbox WHERE event_key=? AND recipient_id=? AND channel='WECHAT'",key,user)!=null)return;
        var bill=db.row("SELECT amount_fen FROM repair_bill WHERE order_id=?",id);
        if(bill==null&&Db.integer(order,"billingVersion")==1&&"PRIVATE".equals(order.get("facilityScope")))bill=db.row("SELECT amount_fen FROM repair_quote WHERE order_id=? AND status='ACCEPTED' ORDER BY revision DESC LIMIT 1",id);
        String timestamp=java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(java.time.ZoneId.of("Asia/Shanghai")).format((java.time.Instant)(order.get("finishedAt")==null?order.get("closedAt"):order.get("finishedAt")));
        String payload;try{payload=json.writeValueAsString(Map.of("kind","COMPLETION","templateId",template,"orderNo",order.get("orderNo"),"amountFen",bill==null?0:Db.id(bill,"amountFen"),"completedAt",timestamp,"page","pages/detail/index?id="+id));}catch(JsonProcessingException e){throw new IllegalStateException(e);}
        if(db.execute("UPDATE wechat_subscription_grant SET remaining_count=remaining_count-1,updated_at=UTC_TIMESTAMP(3) WHERE user_id=? AND template_id=? AND remaining_count>0",user,template)==0)return;
        if(db.execute("INSERT IGNORE INTO notification_outbox(event_key,recipient_id,channel,payload) VALUES (?,?,'WECHAT',?)",key,user,payload)==0)
            db.execute("UPDATE wechat_subscription_grant SET remaining_count=LEAST(20,remaining_count+1),updated_at=UTC_TIMESTAMP(3) WHERE user_id=? AND template_id=?",user,template);
    }

    @Transactional
    public void enqueueAcceptance(Map<String,Object> order,String key){
        if(!sender.acceptanceEnabled())return;
        long user=Db.id(order,"residentId"),id=Db.id(order,"id");String template=sender.acceptanceTemplateId();
        var account=db.row("SELECT wechat_openid FROM sys_user WHERE id=?",user);
        if(account==null||account.get("wechatOpenid")==null)return;
        if(db.row("SELECT id FROM notification_outbox WHERE event_key=? AND recipient_id=? AND channel='WECHAT'",key,user)!=null)return;
        var worker=db.required("SELECT real_name FROM worker_profile WHERE id=?",order.get("workerId"));
        var category=db.required("SELECT name FROM repair_category WHERE id=?",order.get("categoryId"));
        String timestamp=java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(java.time.ZoneId.of("Asia/Shanghai")).format((java.time.Instant)order.get("acceptedAt"));
        String payload;try{payload=json.writeValueAsString(Map.of("kind","ACCEPTANCE","templateId",template,"orderNo",order.get("orderNo"),"service",category.get("name"),"worker",worker.get("realName"),"acceptedAt",timestamp,"page","pages/detail/index?id="+id));}catch(JsonProcessingException e){throw new IllegalStateException(e);}
        if(db.execute("UPDATE wechat_subscription_grant SET remaining_count=remaining_count-1,updated_at=UTC_TIMESTAMP(3) WHERE user_id=? AND template_id=? AND remaining_count>0",user,template)==0)return;
        if(db.execute("INSERT IGNORE INTO notification_outbox(event_key,recipient_id,channel,payload) VALUES (?,?,'WECHAT',?)",key,user,payload)==0)
            db.execute("UPDATE wechat_subscription_grant SET remaining_count=LEAST(20,remaining_count+1),updated_at=UTC_TIMESTAMP(3) WHERE user_id=? AND template_id=?",user,template);
    }

    @Transactional
    public void enqueueOrder(long recipientId, long orderId, String eventKey, String orderNo, String status) {
        if (!sender.orderEnabled()) return;
        var user = db.row("SELECT wechat_openid FROM sys_user WHERE id=?", recipientId);
        if (user == null || user.get("wechatOpenid") == null) return;
        if (db.row("SELECT id FROM notification_outbox WHERE event_key=? AND recipient_id=? AND channel='WECHAT'", eventKey, recipientId) != null) return;
        int reserved = db.execute("UPDATE wechat_subscription_grant SET remaining_count=remaining_count-1,updated_at=UTC_TIMESTAMP(3) " +
                "WHERE user_id=? AND template_id=? AND remaining_count>0", recipientId, sender.templateId());
        if (reserved == 0) return;
        try {
            String payload = json.writeValueAsString(Map.of("orderNo", orderNo, "status", status,
                    "page", "pages/detail/index?id=" + orderId));
            int inserted = db.execute("INSERT IGNORE INTO notification_outbox(event_key,recipient_id,channel,payload) VALUES (?,?,'WECHAT',?)",
                    eventKey, recipientId, payload);
            if (inserted == 0) refund(recipientId);
        } catch (JsonProcessingException ex) {
            refund(recipientId);
            throw new IllegalStateException("Cannot encode order notification", ex);
        }
    }

    @Transactional
    public void enqueueQuote(long user,long order,long quote,String orderNo,long amountFen){
        if(!sender.quoteEnabled())return;String template=sender.quoteTemplateId(),key="quote:"+quote;
        var account=db.row("SELECT wechat_openid FROM sys_user WHERE id=?",user);
        if(account==null||account.get("wechatOpenid")==null)return;
        if(db.row("SELECT id FROM notification_outbox WHERE event_key=? AND recipient_id=? AND channel='WECHAT'",key,user)!=null)return;
        String payload;try{payload=json.writeValueAsString(Map.of("kind","QUOTE","templateId",template,"orderNo",orderNo,"amountFen",amountFen,"page","pages/detail/index?id="+order+"&billing=1"));}catch(Exception e){throw new IllegalStateException("Cannot encode quote notification");}
        if(db.execute("UPDATE wechat_subscription_grant SET remaining_count=remaining_count-1,updated_at=UTC_TIMESTAMP(3) WHERE user_id=? AND template_id=? AND remaining_count>0",user,template)==0)return;
        if(db.execute("INSERT IGNORE INTO notification_outbox(event_key,recipient_id,channel,payload) VALUES (?,?,'WECHAT',?)",key,user,payload)==0)
            db.execute("UPDATE wechat_subscription_grant SET remaining_count=LEAST(20,remaining_count+1),updated_at=UTC_TIMESTAMP(3) WHERE user_id=? AND template_id=?",user,template);
    }
    private void refund(long userId) {
        db.execute("UPDATE wechat_subscription_grant SET remaining_count=LEAST(20,remaining_count+1),updated_at=UTC_TIMESTAMP(3) " +
                "WHERE user_id=? AND template_id=?", userId, sender.templateId());
    }
}
