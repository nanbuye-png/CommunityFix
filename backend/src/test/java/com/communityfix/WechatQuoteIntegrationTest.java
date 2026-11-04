package com.communityfix;

import com.communityfix.common.Db;
import com.communityfix.module.notification.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest(properties={"communityfix.rabbit.enabled=false","communityfix.support.jobs-enabled=false"})
@EnabledIfEnvironmentVariable(named="CF_INTEGRATION",matches="true")
class WechatQuoteIntegrationTest {
 @Autowired Db db;@Autowired WechatSubscriptionService subscriptions;@Autowired ObjectMapper json;
 @Autowired PlatformTransactionManager transactions;
 // enabled() stays false: tests never use the real WeChat transport or scheduled sending.
 @MockBean WechatMessageSender sender;
 long user;
 @BeforeEach void setup(){when(sender.quoteEnabled()).thenReturn(true);when(sender.quoteTemplateId()).thenReturn("synthetic-quote");user=db.insert("sys_user",Db.fields("nickname","Quote test","wechat_openid","synthetic-"+UUID.randomUUID()));}
 @AfterEach void cleanup(){db.execute("DELETE FROM notification_outbox WHERE recipient_id=?",user);db.execute("DELETE FROM wechat_subscription_grant WHERE user_id=?",user);db.execute("DELETE FROM sys_user WHERE id=?",user);}
 void grant(String template){db.execute("INSERT INTO wechat_subscription_grant(user_id,template_id,remaining_count) VALUES (?,?,1)",user,template);}
 long remaining(String template){return Db.id(db.required("SELECT remaining_count FROM wechat_subscription_grant WHERE user_id=? AND template_id=?",user,template),"remainingCount");}
 @Test void quoteReservesOnlyItsOwnGrantAndRetriesDoNotDuplicate() throws Exception {
  grant("synthetic-order");grant("synthetic-quote");subscriptions.enqueueQuote(user,21,31,"CF123",12345);subscriptions.enqueueQuote(user,21,31,"CF123",12345);
  assertEquals(0,remaining("synthetic-quote"));assertEquals(1,remaining("synthetic-order"));
  assertEquals(1,db.count("SELECT COUNT(*) FROM notification_outbox WHERE recipient_id=?",user));
  var payload=json.readTree((String)db.required("SELECT payload FROM notification_outbox WHERE recipient_id=?",user).get("payload"));
  assertEquals("synthetic-quote",payload.path("templateId").asText());assertEquals(12345,payload.path("amountFen").asLong());assertEquals("pages/detail/index?id=21&billing=1",payload.path("page").asText());
 }
 @Test void noQuoteConsentOrNoWechatIdentityDoesNotQueue(){
  grant("synthetic-order");subscriptions.enqueueQuote(user,21,31,"CF123",1);assertEquals(0,db.count("SELECT COUNT(*) FROM notification_outbox WHERE recipient_id=?",user));
  grant("synthetic-quote");db.execute("UPDATE sys_user SET wechat_openid=NULL WHERE id=?",user);subscriptions.enqueueQuote(user,21,31,"CF123",1);assertEquals(1,remaining("synthetic-quote"));assertEquals(0,db.count("SELECT COUNT(*) FROM notification_outbox WHERE recipient_id=?",user));
 }
 @Test void quoteRollbackAlsoRollsBackGrantReservationAndOutbox(){
  grant("synthetic-quote");new TransactionTemplate(transactions).executeWithoutResult(status->{subscriptions.enqueueQuote(user,21,31,"CF123",1);status.setRollbackOnly();});
  assertEquals(1,remaining("synthetic-quote"));assertEquals(0,db.count("SELECT COUNT(*) FROM notification_outbox WHERE recipient_id=?",user));
 }
}
