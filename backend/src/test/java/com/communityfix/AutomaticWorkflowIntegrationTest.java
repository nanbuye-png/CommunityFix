package com.communityfix;

import com.communityfix.common.Db;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={"communityfix.orders.auto-approve=true","communityfix.support.jobs-enabled=false"})
@EnabledIfEnvironmentVariable(named="CF_INTEGRATION",matches="true")
class AutomaticWorkflowIntegrationTest {
 @Autowired com.communityfix.module.ai.AiDailyUsage usage;
 @Test void dailyUsageCountsUnknownSeparately(){
  var scene=com.communityfix.module.ai.ModelRoutes.Scene.REPAIR;
  String query="SELECT COALESCE(SUM(requests),0) AS requests,COALESCE(SUM(reported_tokens),0) AS tokens,COALESCE(SUM(unknown_usage),0) AS unknowns FROM ai_daily_usage WHERE usage_date=DATE(DATE_ADD(UTC_TIMESTAMP(),INTERVAL 8 HOUR)) AND scene='REPAIR'";
  var before=db.required(query);
  usage.record(scene,new com.communityfix.module.ai.ChatCompletionsClient.Usage(10L,5L,15L));usage.record(scene,null);
  var after=db.required(query);
  assertEquals(Db.id(before,"requests")+2,Db.id(after,"requests"));assertEquals(Db.id(before,"tokens")+15,Db.id(after,"tokens"));assertEquals(Db.id(before,"unknowns")+1,Db.id(after,"unknowns"));
 }
 @Autowired com.communityfix.module.notification.NotificationValidity validity;
 @Autowired org.springframework.transaction.PlatformTransactionManager transactions;
 @Test void priceVersionsAndStaleNotificationsAreHandled() throws Exception {
  var o=create("PRIVATE");long id=o.path("id").asLong(),community=o.path("communityId").asLong(),residentId=o.path("residentId").asLong();String path="/repair/orders/"+id+"/billing";
  var prices=ok(call(property,"/reference-prices?communityId="+community,null));assertTrue(prices.get(0).path("categoryId").asLong()>0);
  int priorHistory=ok(call(property,"/reference-prices/history?communityId="+community+"&categoryId=1",null)).size();
  var q=ok(call(property,path+"/reference-price",Map.of("version",-1,"laborFen",8000,"materialFen",2000,"basis","测试价格"))).path("quotes").get(0);
  assertEquals(409,call(property,path+"/reference-price",Map.of("version",-1,"laborFen",9000,"materialFen",2000,"basis","旧页面")).getStatusCode().value());
  assertEquals(priorHistory+1,ok(call(property,"/reference-prices/history?communityId="+community+"&categoryId=1",null)).size());
  String template="guard-"+java.util.UUID.randomUUID();String payload=new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(Map.of("kind","CONFIRMATION","templateId",template,"orderNo",o.path("orderNo").asText(),"amountFen",10000));
  long outbox=db.insert("notification_outbox",Db.fields("event_key","quote:"+q.path("id").asLong(),"recipient_id",residentId,"channel","WECHAT","payload",payload,"status","PROCESSING","attempts",1));
  try{
   db.execute("INSERT INTO wechat_subscription_grant(user_id,template_id,remaining_count) VALUES (?,?,0)",residentId,template);
   assertNull(validity.invalidReason(outbox,payload));
   ok(call(resident,path+"/decision",Map.of("quoteId",q.path("id").asLong(),"accepted",true)));
   String invalid=validity.invalidReason(outbox,payload);assertNotNull(invalid);validity.skip(outbox,1,invalid,payload);validity.skip(outbox,1,invalid,payload);
   assertEquals("SKIPPED",db.required("SELECT status FROM notification_outbox WHERE id=?",outbox).get("status"));
   assertEquals(1,Db.id(db.required("SELECT remaining_count FROM wechat_subscription_grant WHERE user_id=? AND template_id=?",residentId,template),"remainingCount"));
  }finally{db.execute("DELETE FROM notification_outbox WHERE id=?",outbox);db.execute("DELETE FROM wechat_subscription_grant WHERE user_id=? AND template_id=?",residentId,template);}
 }
 @Autowired TestRestTemplate rest;@Autowired Db db;@Value("${communityfix.bootstrap-password}") String password;
 String resident,property,worker;long room,wid;List<Long> orders=new ArrayList<>();
 ResponseEntity<JsonNode> call(String token,String path,Object body){var h=new HttpHeaders();if(token!=null)h.setBearerAuth(token);h.set("Idempotency-Key",UUID.randomUUID().toString());return rest.exchange("/api"+path,body==null?HttpMethod.GET:HttpMethod.POST,new HttpEntity<>(body,h),JsonNode.class);}
 JsonNode ok(ResponseEntity<JsonNode> r){assertEquals(200,r.getStatusCode().value(),()->String.valueOf(r.getBody()));return r.getBody().path("data");}
 String login(String name){return ok(call(null,"/auth/login",Map.of("username",name,"password",password))).path("token").asText();}
 JsonNode create(String scope){var o=ok(call(resident,"/repair/orders",Map.of("roomId",room,"categoryId",1,"title","自动流程测试","description","检修","priority","NORMAL","facilityScope",scope,"locationText","楼道")));orders.add(o.path("id").asLong());return o;}
 JsonNode act(String token,long id,String action,Map<String,Object> extra){var o=ok(call(resident,"/repair/orders/"+id,null));var b=new HashMap<>(extra);b.put("version",o.path("version").asInt());return ok(call(token,"/repair/orders/"+id+"/"+action,b));}
 @BeforeEach void setup(){resident=login("resident");property=login("property");worker=login("worker");room=Db.id(db.required("SELECT room_id FROM resident_room_binding b JOIN sys_user u ON u.id=b.user_id WHERE u.username='resident' AND b.status='APPROVED' LIMIT 1"),"roomId");wid=Db.id(db.required("SELECT w.id FROM worker_profile w JOIN sys_user u ON u.id=w.user_id WHERE u.username='worker'"),"id");}
 @AfterEach void cleanup(){for(long id:orders){db.execute("DELETE p FROM repair_reference_price p JOIN repair_order o ON p.community_id=o.community_id AND p.category_id=o.category_id WHERE o.id=?",id);db.execute("UPDATE repair_order SET status='CANCELED' WHERE id=? AND status<>'CLOSED'",id);}}
 @Test void scopeCorrectionBeforeFirstDispatchPreservesQuoteHistory(){
  var o=create("PRIVATE");long id=o.path("id").asLong();String path="/repair/orders/"+id+"/billing";
  var q=ok(call(property,path+"/reference-price",Map.of("laborFen",8000,"materialFen",2000,"basis","测试依据"))).path("quotes").get(0);
  ok(call(resident,path+"/decision",Map.of("quoteId",q.path("id").asLong(),"accepted",true)));
  assertEquals(409,call(property,path+"/scope",Map.of("version",0,"facilityScope","PUBLIC","locationText","楼道","reason","纠正误选")).getStatusCode().value());
  ok(call(property,path+"/scope",Map.of("version",1,"facilityScope","PUBLIC","locationText","楼道","reason","纠正误选")));
  assertEquals("SUPERSEDED",ok(call(resident,path,null)).path("quotes").get(0).path("status").asText());
  ok(call(property,path+"/scope",Map.of("version",2,"facilityScope","PRIVATE","locationText","室内","reason","再次核实")));
  var revised=ok(call(resident,path,null)).path("quotes").get(0);assertEquals(2,revised.path("revision").asInt());assertEquals("PROPOSED",revised.path("status").asText());
  assertEquals(409,call(property,"/repair/orders/"+id+"/assign",Map.of("version",3,"workerId",wid)).getStatusCode().value());
 }
 @Test void publicAutomaticApprovalRequiresPropertyConfirmationAtDispatch(){
  var o=create("PUBLIC");long id=o.path("id").asLong();String path="/repair/orders/"+id;
  assertEquals(400,call(property,path+"/assign",Map.of("version",1,"workerId",wid)).getStatusCode().value());
  act(property,id,"assign",Map.of("workerId",wid,"confirmedFacilityScope","PUBLIC"));
  assertEquals(409,call(property,path+"/billing/scope",Map.of("version",2,"facilityScope","PRIVATE","locationText","室内","reason","不允许派单后改账")).getStatusCode().value());
 }
 @Test void referencePricesCreateQuotesAndWorkersCanOnlyProposeRevisions(){
  var first=create("PRIVATE");long id=first.path("id").asLong();String path="/repair/orders/"+id+"/billing";
  assertEquals("PENDING_DISPATCH",first.path("status").asText());assertEquals(1,first.path("version").asInt());
  assertEquals(2,ok(call(resident,"/repair/orders/"+id+"/logs",null)).size());
  assertTrue(ok(call(resident,path,null)).path("quotes").isEmpty());
  var price=Map.of("laborFen",8000,"materialFen",2000,"basis","供应商参考价，测试数据");
  assertEquals(403,call(resident,path+"/reference-price",price).getStatusCode().value());
  var q=ok(call(property,path+"/reference-price",price)).path("quotes").get(0);
  assertEquals(10000,q.path("amountFen").asInt());assertEquals("PROPOSED",q.path("status").asText());
  ok(call(property,path+"/reference-price",Map.of("version",0,"laborFen",9000,"materialFen",2000,"basis","修订测试参考价")));
  assertEquals(10000,ok(call(resident,path,null)).path("quotes").get(0).path("amountFen").asInt());
  var second=create("PRIVATE");assertEquals(11000,ok(call(resident,"/repair/orders/"+second.path("id").asLong()+"/billing",null)).path("quotes").get(0).path("amountFen").asInt());
  var publicOrder=create("PUBLIC");assertTrue(ok(call(resident,"/repair/orders/"+publicOrder.path("id").asLong()+"/billing",null)).path("quotes").isEmpty());
  ok(call(resident,path+"/decision",Map.of("quoteId",q.path("id").asLong(),"accepted",true)));
  act(property,id,"assign",Map.of("workerId",wid));act(worker,id,"accept",Map.of());
  assertEquals(403,call(worker,path+"/reference-price",price).getStatusCode().value());
  var adjustment=Map.of("revision",1,"reason","现场核实完整费用","items",List.of(Map.of("name","人工费","quantity",1,"unitPriceFen",12000)));
  assertEquals(409,call(worker,path+"/quotes",Map.of("revision",0,"reason","旧版本","items",adjustment.get("items"))).getStatusCode().value());
  var revised=ok(call(worker,path+"/quotes",adjustment)).path("quotes").get(0);
  assertEquals(409,call(worker,"/repair/orders/"+id+"/start",Map.of("version",ok(call(resident,"/repair/orders/"+id,null)).path("version").asInt())).getStatusCode().value());
  ok(call(resident,path+"/decision",Map.of("quoteId",revised.path("id").asLong(),"accepted",true)));
  act(worker,id,"start",Map.of());act(worker,id,"finish",Map.of("content","完成"));act(resident,id,"confirm",Map.of());
  assertEquals(12000,ok(call(resident,path,null)).path("bill").path("amountFen").asInt());
  var messages=db.rows("SELECT event_key FROM notification WHERE order_id=? AND user_id=?",id,first.path("residentId").asLong());
  assertEquals(5,messages.size());assertEquals(2,messages.stream().filter(m->m.get("eventKey").toString().startsWith("quote:")).count());assertEquals(1,messages.stream().filter(m->m.get("eventKey").toString().startsWith("bill:")).count());assertTrue(messages.stream().anyMatch(m->m.get("eventKey").toString().endsWith(":accept")));assertTrue(messages.stream().anyMatch(m->m.get("eventKey").toString().endsWith(":finish")));
 }
}
