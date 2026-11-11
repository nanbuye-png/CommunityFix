package com.communityfix;

import com.communityfix.common.Db;
import com.communityfix.module.billing.PaymentAttemptExpiry;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={"communityfix.orders.auto-approve=false","communityfix.billing.mock-enabled=true","communityfix.wechat.order-template-id="})
@EnabledIfEnvironmentVariable(named="CF_INTEGRATION",matches="true")
class BillingIntegrationTest {
 @Autowired TestRestTemplate rest;@Autowired Db db;@Value("${communityfix.bootstrap-password}") String password;
 @Autowired PaymentAttemptExpiry expiry;
 @Autowired com.communityfix.module.notification.NotificationValidity validity;
 @Test void paidReminderIsSkippedWithoutRefundingAnUncertainSend() throws Exception {
  var created=create("PRIVATE");act(property,"review",Map.of("approved",true));accept(quote(0,1000),true);begin();finish();
  var bill=ok(call(resident,path()+"/billing",null)).path("bill");long residentId=created.path("residentId").asLong();
  String template="payment-guard-"+UUID.randomUUID();
  String payload=new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(Map.of("kind","PAYMENT_DUE","templateId",template,"orderNo",created.path("orderNo").asText(),"amountFen",2000));
  long outbox=db.insert("notification_outbox",Db.fields("event_key","bill:"+bill.path("id").asText(),"recipient_id",residentId,"channel","WECHAT","payload",payload,"status","PROCESSING","attempts",2,"send_started",true));
  try{
   db.execute("INSERT INTO wechat_subscription_grant(user_id,template_id,remaining_count) VALUES (?,?,0)",residentId,template);
   assertNull(validity.invalidReason(outbox,payload));
   var attempt=ok(call(resident,path()+"/billing/payment-attempts",Map.of("billId",bill.path("id").asText()))).path("paymentAttempt");
   ok(call(resident,path()+"/billing/mock-pay",Map.of("billId",bill.path("id").asText(),"attemptNo",attempt.path("attemptNo").asText())));
   String invalid=validity.invalidReason(outbox,payload);assertNotNull(invalid);
   validity.skip(outbox,1,invalid,payload);assertEquals("PROCESSING",db.required("SELECT status FROM notification_outbox WHERE id=?",outbox).get("status"));
   validity.skip(outbox,2,invalid,payload);validity.skip(outbox,2,invalid,payload);
   assertEquals("SKIPPED",db.required("SELECT status FROM notification_outbox WHERE id=?",outbox).get("status"));
   assertEquals(0,Db.id(db.required("SELECT remaining_count FROM wechat_subscription_grant WHERE user_id=? AND template_id=?",residentId,template),"remainingCount"));
  }finally{db.execute("DELETE FROM notification_outbox WHERE id=?",outbox);db.execute("DELETE FROM wechat_subscription_grant WHERE user_id=? AND template_id=?",residentId,template);}
 }
 @Test void aftercareConcurrentCreationAndActionRetryDoNotDuplicateRecords(){
  create("PUBLIC");act(property,"review",Map.of("approved",true));begin();finish();
  String url=path()+"/aftercare";
  var first=CompletableFuture.supplyAsync(()->call(resident,url,Map.of("content","故障复现")));
  var second=CompletableFuture.supplyAsync(()->call(resident,url,Map.of("content","故障复现")));
  var results=List.of(first.join(),second.join());
  assertEquals(1,results.stream().filter(r->r.getStatusCode().value()==200).count());
  var created=ok(results.stream().filter(r->r.getStatusCode().value()==200).findFirst().orElseThrow());
  String actionUrl="/api"+url+"/"+created.path("id").asText();
  var headers=new HttpHeaders();headers.setBearerAuth(property);headers.set("Idempotency-Key",UUID.randomUUID().toString());
  var body=new HttpEntity<>(Map.of("version",0,"action","accept","content","已受理"),headers);
  var one=ok(rest.exchange(actionUrl,HttpMethod.POST,body,JsonNode.class));
  var two=ok(rest.exchange(actionUrl,HttpMethod.POST,body,JsonNode.class));assertEquals(one,two);
  assertEquals(2,ok(call(resident,url,null)).get(0).path("logs").size());
 }
 @Test void aftercareKeepsBillAndOrderAndRequiresResidentConfirmation(){
  create("PRIVATE");assertEquals(400,call(resident,path()+"/aftercare",Map.of("content","尚未验收")).getStatusCode().value());
  act(property,"review",Map.of("approved",true));accept(quote(0,1000),true);begin();finish();
  var bill=ok(call(resident,path()+"/billing",null)).path("bill");
  var request=ok(call(resident,path()+"/aftercare",Map.of("content","灯又不亮了")));
  String after=path()+"/aftercare/"+request.path("id").asText();
  assertEquals(400,call(resident,path()+"/aftercare",Map.of("content","重复申请")).getStatusCode().value());
  assertEquals(403,call(worker,path()+"/aftercare",null).getStatusCode().value());
  assertNotEquals(200,call(login("resident2"),path()+"/aftercare",null).getStatusCode().value());
  assertNotEquals(200,call(login("property2"),after,Map.of("version",0,"action","accept","content","越权")).getStatusCode().value());
  ok(call(property,after,Map.of("version",0,"action","accept","content","已受理")));
  assertEquals(409,call(property,after,Map.of("version",0,"action","resolve","content","旧页面")).getStatusCode().value());
  ok(call(property,after,Map.of("version",1,"action","resolve","content","已检查并处理")));
  assertEquals(403,call(property,after,Map.of("version",2,"action","confirm","content","不能替居民确认")).getStatusCode().value());
  ok(call(resident,after,Map.of("version",2,"action","reopen","content","仍未解决")));
  ok(call(property,after,Map.of("version",3,"action","resolve","content","再次处理完成")));
  ok(call(resident,after,Map.of("version",4,"action","confirm","content","已解决")));
  assertEquals("CLOSED",order().path("status").asText());assertEquals(bill,ok(call(resident,path()+"/billing",null)).path("bill"));
  assertEquals(6,ok(call(resident,path()+"/aftercare",null)).get(0).path("logs").size());
  var next=ok(call(resident,path()+"/aftercare",Map.of("content","新问题")));
  ok(call(property,path()+"/aftercare/"+next.path("id").asText(),Map.of("version",0,"action","reject","content","说明不予受理原因")));
 }
 String resident,property,worker;long room,wid;String id;
 @BeforeEach void setup(){resident=login("resident");property=login("property");worker=login("worker");room=Db.id(db.required("SELECT room_id FROM resident_room_binding b JOIN sys_user u ON u.id=b.user_id WHERE u.username='resident' AND b.status='APPROVED' LIMIT 1"),"roomId");wid=Db.id(db.required("SELECT w.id FROM worker_profile w JOIN sys_user u ON u.id=w.user_id WHERE u.username='worker'"),"id");}
 String login(String user){return ok(call(null,"/auth/login",Map.of("username",user,"password",password))).path("token").asText();}
 ResponseEntity<JsonNode> call(String token,String path,Object body){HttpHeaders h=new HttpHeaders();if(token!=null)h.setBearerAuth(token);h.set("Idempotency-Key",UUID.randomUUID().toString());return rest.exchange("/api"+path,body==null?HttpMethod.GET:HttpMethod.POST,new HttpEntity<>(body,h),JsonNode.class);}
 JsonNode ok(ResponseEntity<JsonNode> r){assertEquals(200,r.getStatusCode().value(),()->String.valueOf(r.getBody()));return r.getBody().path("data");}
 JsonNode create(String scope){var o=ok(call(resident,"/repair/orders",Map.of("roomId",room,"categoryId",1,"title","费用测试","description","灯具检修","priority","NORMAL","facilityScope",scope,"locationText","公共楼道")));id=o.path("id").asText();return o;}
 String path(){return "/repair/orders/"+id;}
 JsonNode order(){return ok(call(resident,path(),null));}
 JsonNode act(String token,String action,Map<String,Object> extra){var body=new HashMap<>(extra);body.put("version",order().path("version").asInt());return ok(call(token,path()+"/"+action,body));}
 JsonNode quote(int revision,long price){return ok(call(property,path()+"/billing/quotes",Map.of("revision",revision,"reason","完整维修报价","items",List.of(Map.of("name","人工费","quantity",2,"unitPriceFen",price)))));}
 void accept(JsonNode data,boolean yes){ok(call(resident,path()+"/billing/decision",Map.of("quoteId",data.path("quotes").get(0).path("id").asText(),"accepted",yes)));}
 void begin(){act(property,"assign",Map.of("workerId",wid));act(worker,"accept",Map.of());act(worker,"start",Map.of());}
 void finish(){act(worker,"finish",Map.of("content","检修完成"));act(resident,"confirm",Map.of());}
 @AfterEach void release(){if(id!=null){var o=db.row("SELECT status FROM repair_order WHERE id=?",id);if(o!=null&&!Set.of("CLOSED","CANCELED","REJECTED").contains(o.get("status")))db.execute("UPDATE repair_order SET status='CANCELED' WHERE id=?",id);}}
 @Test void quoteRepairAcceptanceAndMockPaymentAreOneConsistentFlow(){
  create("PRIVATE");act(property,"review",Map.of("approved",true));
  assertEquals(409,call(property,path()+"/assign",Map.of("version",order().path("version").asInt(),"workerId",wid)).getStatusCode().value());
  var q=quote(0,6500);accept(q,true);begin();
  assertEquals(409,call(resident,path()+"/billing/mock-pay",Map.of("billId",1)).getStatusCode().value());
  finish();var data=ok(call(resident,path()+"/billing",null));assertEquals(13000,data.path("bill").path("amountFen").asLong());assertEquals("UNPAID",data.path("bill").path("status").asText());
  String bill=data.path("bill").path("id").asText();
  assertTrue(data.path("paymentAttempt").isNull());
  var startBody=Map.of("billId",bill);
  String start=path()+"/billing/payment-attempts",pay=path()+"/billing/mock-pay";
  String other=login("resident2");
  assertEquals(404,call(other,start,startBody).getStatusCode().value());
  assertEquals("PAYMENT_ATTEMPT_REQUIRED",call(resident,pay,startBody).getBody().path("errorCode").asText());
  var first=CompletableFuture.supplyAsync(()->ok(call(resident,start,startBody)).path("paymentAttempt"));
  var second=CompletableFuture.supplyAsync(()->ok(call(resident,start,startBody)).path("paymentAttempt"));
  var attempt=first.join();assertEquals(attempt,second.join());
  assertEquals(Duration.ofMinutes(30),Duration.between(Instant.parse(attempt.path("createdAt").asText()),Instant.parse(attempt.path("expiresAt").asText())));
  assertEquals(attempt,ok(call(resident,path()+"/billing",null)).path("paymentAttempt"));
  String old=attempt.path("attemptNo").asText();
  var oldBody=Map.of("billId",bill,"attemptNo",old);
  assertEquals(404,call(other,pay,oldBody).getStatusCode().value());
  db.execute("UPDATE repair_payment_attempt SET expires_at=UTC_TIMESTAMP(3) WHERE attempt_no=?",old);
  assertEquals("PAYMENT_ATTEMPT_EXPIRED",call(resident,pay,oldBody).getBody().path("errorCode").asText());
  var expired=ok(call(resident,path()+"/billing",null));
  assertEquals("EXPIRED",expired.path("paymentAttempt").path("status").asText());
  assertEquals("UNPAID",expired.path("bill").path("status").asText());
  assertEquals("CLOSED",order().path("status").asText());
  expiry.closeExpired();
  assertEquals("EXPIRED",db.required("SELECT status FROM repair_payment_attempt WHERE attempt_no=?",old).get("status"));
  var retry=ok(call(resident,start,startBody)).path("paymentAttempt");
  String newNo=retry.path("attemptNo").asText();
  assertNotEquals(old,newNo);
  assertEquals(409,call(resident,pay,oldBody).getStatusCode().value());
  var confirm=Map.of("billId",bill,"attemptNo",newNo,"amountFen","1");
  var confirmation1=CompletableFuture.supplyAsync(()->call(resident,pay,confirm));
  var confirmation2=CompletableFuture.supplyAsync(()->call(resident,pay,confirm));
  var paid=ok(confirmation1.join());ok(confirmation2.join());
  assertEquals("MOCK_PAID",paid.path("bill").path("status").asText());assertEquals(13000,paid.path("payments").get(0).path("amountFen").asLong());
  assertEquals("SUCCEEDED",paid.path("paymentAttempt").path("status").asText());
  assertEquals(newNo,paid.path("payments").get(0).path("paymentNo").asText());
  ok(call(resident,pay,confirm));assertEquals(409,call(resident,pay,oldBody).getStatusCode().value());
  assertEquals(2,db.count("SELECT COUNT(*) FROM repair_payment_attempt WHERE bill_id=?",bill));
  assertEquals(0,db.count("SELECT COUNT(*) FROM notification WHERE order_id=? AND event_key=? AND user_id=(SELECT payer_id FROM repair_bill WHERE id=?)",id,"mock-paid:"+bill,bill));
  assertEquals(1,db.count("SELECT COUNT(*) FROM repair_payment WHERE bill_id=?",bill));assertEquals(1,db.count("SELECT COUNT(*) FROM repair_bill WHERE order_id=?",id));
 }
 @Test void additionalQuoteMustBeAcceptedAndStaleRevisionCannotWin(){
  create("PRIVATE");act(property,"review",Map.of("approved",true));accept(quote(0,1000),true);begin();
  var added=quote(1,1500);
  assertEquals(409,call(worker,path()+"/finish",Map.of("version",order().path("version").asInt(),"content","完成")).getStatusCode().value());
  assertEquals(409,call(property,path()+"/billing/quotes",Map.of("revision",1,"reason","过期版本","items",List.of(Map.of("name","人工","quantity",1,"unitPriceFen",1)))).getStatusCode().value());
  accept(added,true);finish();assertEquals(3000,ok(call(resident,path()+"/billing",null)).path("bill").path("amountFen").asLong());
 }
 @Test void publicAndHistoricalOrdersNeverCreateBills(){
  create("PUBLIC");act(property,"review",Map.of("approved",true));begin();finish();assertTrue(ok(call(resident,path()+"/billing",null)).path("bill").isNull());
  create("PRIVATE");db.execute("UPDATE repair_order SET billing_version=0,facility_scope='LEGACY' WHERE id=?",id);act(property,"review",Map.of("approved",true));begin();finish();assertTrue(ok(call(resident,path()+"/billing",null)).path("bill").isNull());
 }
 @Test void rejectedIncreaseKeepsOriginalPriceAndOtherUsersCannotDecide(){
  create("PRIVATE");act(property,"review",Map.of("approved",true));accept(quote(0,1000),true);var added=quote(1,2000);
  assertEquals(404,call(login("resident2"),path()+"/billing/decision",Map.of("quoteId",added.path("quotes").get(0).path("id").asText(),"accepted",true)).getStatusCode().value());
  assertEquals(404,call(login("property2"),path()+"/billing",null).getStatusCode().value());
  accept(added,false);begin();finish();assertEquals(2000,ok(call(resident,path()+"/billing",null)).path("bill").path("amountFen").asLong());
 }
 @Test void invalidAmountsAndCancelledOrdersCannotBeCharged(){
  create("PRIVATE");act(property,"review",Map.of("approved",true));
  assertEquals(400,call(property,path()+"/billing/quotes",Map.of("revision",0,"reason","错误金额","items",List.of(Map.of("name","费用","quantity",1,"unitPriceFen",-1)))).getStatusCode().value());
  quote(0,500);act(resident,"cancel",Map.of("reason","不维修了"));
  assertEquals(409,call(resident,path()+"/billing/mock-pay",Map.of("billId",1)).getStatusCode().value());
  assertEquals(0,db.count("SELECT COUNT(*) FROM repair_bill WHERE order_id=?",id));
 }
}
