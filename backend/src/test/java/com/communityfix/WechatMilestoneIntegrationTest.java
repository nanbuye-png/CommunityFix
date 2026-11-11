package com.communityfix;

import com.communityfix.common.Db;
import com.communityfix.module.notification.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import java.time.Instant;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest(properties={"communityfix.rabbit.enabled=false","communityfix.support.jobs-enabled=false"})
@EnabledIfEnvironmentVariable(named="CF_INTEGRATION",matches="true")
class WechatMilestoneIntegrationTest {
 @Autowired Db db;@Autowired WechatSubscriptionService subscriptions;@Autowired ObjectMapper json;@Autowired PlatformTransactionManager transactions;
 @MockitoBean WechatMessageSender sender;
 @Test void milestonesReserveIndependentGrantsDeduplicateAndRollback() throws Exception {
  when(sender.acceptanceEnabled()).thenReturn(true);when(sender.completionEnabled()).thenReturn(true);
  when(sender.acceptanceTemplateId()).thenReturn("acceptance-test");when(sender.completionTemplateId()).thenReturn("completion-test");
  when(sender.confirmationEnabled()).thenReturn(true);when(sender.paymentEnabled()).thenReturn(true);
  when(sender.confirmationTemplateId()).thenReturn("confirmation-test");when(sender.paymentTemplateId()).thenReturn("payment-test");
  long user=db.insert("sys_user",Db.fields("nickname","模板测试","wechat_openid","synthetic-"+UUID.randomUUID()));
  String key="milestone:"+UUID.randomUUID();var tx=new TransactionTemplate(transactions);
  var worker=db.required("SELECT id FROM worker_profile LIMIT 1");var category=db.required("SELECT id FROM repair_category LIMIT 1");
  var order=Db.fields("billingVersion",0,"id",999999999L,"residentId",user,"workerId",worker.get("id"),"categoryId",category.get("id"),"orderNo","CF-TEST","acceptedAt",Instant.parse("2026-11-10T01:30:00Z"),"closedAt",Instant.parse("2026-11-10T02:30:00Z"));
  try{
   order.put("createdAt",Instant.parse("2026-11-10T00:00:00Z"));order.put("title","灯具维修");
   subscriptions.enqueueConfirmation(order,user,12345);subscriptions.enqueuePaymentDue(order,user,12345);
   assertEquals(0,db.count("SELECT COUNT(*) FROM notification_outbox WHERE recipient_id=?",user));
   db.execute("INSERT INTO wechat_subscription_grant(user_id,template_id,remaining_count) VALUES (?,'acceptance-test',1),(?,'completion-test',1)",user,user);
   db.execute("INSERT INTO wechat_subscription_grant(user_id,template_id,remaining_count) VALUES (?,'confirmation-test',1),(?,'payment-test',1)",user,user);
   tx.executeWithoutResult(s->{subscriptions.enqueueConfirmation(order,user,12345);subscriptions.enqueuePaymentDue(order,user,12345);s.setRollbackOnly();});
   tx.executeWithoutResult(s->{subscriptions.enqueueAcceptance(order,key+":accept");s.setRollbackOnly();});
   assertEquals(0,db.count("SELECT COUNT(*) FROM notification_outbox WHERE recipient_id=?",user));
   assertEquals(1,db.required("SELECT remaining_count FROM wechat_subscription_grant WHERE user_id=? AND template_id='acceptance-test'",user).get("remainingCount"));
   tx.executeWithoutResult(s->{subscriptions.enqueueAcceptance(order,key+":accept");subscriptions.enqueueAcceptance(order,key+":accept");});
   assertEquals(1,db.required("SELECT remaining_count FROM wechat_subscription_grant WHERE user_id=? AND template_id='completion-test'",user).get("remainingCount"));
   tx.executeWithoutResult(s->{subscriptions.enqueueCompletion(order,key+":complete");subscriptions.enqueueCompletion(order,key+":complete");});
   assertEquals(2,db.count("SELECT COUNT(*) FROM notification_outbox WHERE recipient_id=?",user));
   assertEquals(2,db.count("SELECT COUNT(*) FROM wechat_subscription_grant WHERE user_id=? AND remaining_count=1",user));
   tx.executeWithoutResult(s->{subscriptions.enqueueConfirmation(order,user,12345);subscriptions.enqueueConfirmation(order,user,99999);subscriptions.enqueuePaymentDue(order,user,12345);subscriptions.enqueuePaymentDue(order,user,99999);});
   assertEquals(4,db.count("SELECT COUNT(*) FROM notification_outbox WHERE recipient_id=?",user));
   for(String event:new String[]{"quote:"+user,"bill:"+user}){
    var payload=json.readTree((String)db.required("SELECT payload FROM notification_outbox WHERE event_key=? AND recipient_id=?",event,user).get("payload"));
    assertEquals(12345,payload.path("amountFen").asLong());assertEquals("2026-11-10 08:00:00",payload.path("createdAt").asText());assertTrue(payload.path("page").asText().endsWith("&billing=1"));
   }
   var accepted=json.readTree((String)db.required("SELECT payload FROM notification_outbox WHERE event_key=?",key+":accept").get("payload"));
   assertEquals("2026-11-10 09:30:00",accepted.path("acceptedAt").asText());assertEquals("acceptance-test",accepted.path("templateId").asText());
   var done=json.readTree((String)db.required("SELECT payload FROM notification_outbox WHERE event_key=?",key+":complete").get("payload"));
   assertEquals(0,done.path("amountFen").asLong());assertEquals("2026-11-10 10:30:00",done.path("completedAt").asText());
   assertEquals(0,db.count("SELECT COUNT(*) FROM wechat_subscription_grant WHERE user_id=? AND remaining_count<>0",user));
  }finally{db.execute("DELETE FROM notification_outbox WHERE recipient_id=?",user);db.execute("DELETE FROM wechat_subscription_grant WHERE user_id=?",user);db.execute("DELETE FROM sys_user WHERE id=?",user);}
 }
}
