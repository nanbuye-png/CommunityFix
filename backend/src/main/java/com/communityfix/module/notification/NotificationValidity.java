package com.communityfix.module.notification;

import com.communityfix.common.Db;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.Set;

@Service
public class NotificationValidity {
 private final Db db;
 public NotificationValidity(Db db){this.db=db;}
 public String invalidReason(long id,String payload) throws Exception {
  var event=new ObjectMapper().readTree(payload);String kind=event.path("kind").asText();
  if("COMPLETION".equals(kind)){
   var outbox=db.required("SELECT event_key,recipient_id FROM notification_outbox WHERE id=?",id);
   String key=(String)outbox.get("eventKey");
   if(key==null||!key.matches("order:[0-9]+:[0-9]+:finish"))return "旧完成事件无法确认维修轮次";
   String[] parts=key.split(":");
   var order=db.row("SELECT resident_id,order_no,status,version FROM repair_order WHERE id=?",Long.parseLong(parts[1]));
   return order!=null&&Db.id(order,"residentId")==Db.id(outbox,"recipientId")&&event.path("orderNo").asText().equals(order.get("orderNo"))&&"PENDING_CONFIRM".equals(order.get("status"))&&Db.integer(order,"version")==Integer.parseInt(parts[2])?null:"已验收、返修或维修轮次已变化";
  }
  if(!Set.of("QUOTE","CONFIRMATION","PAYMENT_DUE").contains(kind))return null;
  var outbox=db.required("SELECT event_key,recipient_id FROM notification_outbox WHERE id=?",id);
  String key=(String)outbox.get("eventKey");boolean payment=kind.equals("PAYMENT_DUE");
  if(!key.matches(payment?"bill:[0-9]+":"quote:[0-9]+"))return "无法确认费用事件对应的业务记录";
  long target=Long.parseLong(key.substring(key.indexOf(':')+1));
  var record=db.row(payment?
   "SELECT b.status,b.amount_fen,o.status AS order_status,o.order_no,o.resident_id FROM repair_bill b JOIN repair_order o ON o.id=b.order_id WHERE b.id=?":
   "SELECT q.status,q.amount_fen,o.status AS order_status,o.order_no,o.resident_id FROM repair_quote q JOIN repair_order o ON o.id=q.order_id WHERE q.id=?",target);
  if(record==null||Db.id(record,"residentId")!=Db.id(outbox,"recipientId")||!event.path("orderNo").asText().equals(record.get("orderNo")))return "费用记录不存在或收件人不匹配";
  if(Db.id(record,"amountFen")!=event.path("amountFen").asLong(-1))return "费用已变化";
  if(payment)return "UNPAID".equals(record.get("status"))&&"CLOSED".equals(record.get("orderStatus"))?null:"账单已支付或不再待付";
  return "PROPOSED".equals(record.get("status"))&&Set.of("PENDING_DISPATCH","ASSIGNED","ACCEPTED","IN_PROGRESS").contains(record.get("orderStatus"))?null:"报价已回复、被替换或订单已终止";
 }
 @Transactional public void skip(long id,int attempt,String reason,String payload) throws Exception {
  var row=db.required("SELECT status,attempts,send_started,recipient_id FROM notification_outbox WHERE id=? FOR UPDATE",id);
  if(!"PROCESSING".equals(row.get("status"))||Db.integer(row,"attempts")!=attempt)return;
  db.execute("UPDATE notification_outbox SET status='SKIPPED',last_error=?,locked_until=NULL,next_retry_at=NULL,updated_at=UTC_TIMESTAMP(3) WHERE id=?",reason,id);
  boolean started=Boolean.TRUE.equals(row.get("sendStarted"))||row.get("sendStarted") instanceof Number n&&n.intValue()!=0;
  String template=new ObjectMapper().readTree(payload).path("templateId").asText();
  if(!started&&!template.isBlank())db.execute("UPDATE wechat_subscription_grant SET remaining_count=LEAST(20,remaining_count+1),updated_at=UTC_TIMESTAMP(3) WHERE user_id=? AND template_id=?",row.get("recipientId"),template);
 }
}
