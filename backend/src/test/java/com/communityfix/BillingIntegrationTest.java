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

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={"communityfix.billing.mock-enabled=true","communityfix.wechat.order-template-id="})
@EnabledIfEnvironmentVariable(named="CF_INTEGRATION",matches="true")
class BillingIntegrationTest {
 @Autowired TestRestTemplate rest;@Autowired Db db;@Value("${communityfix.bootstrap-password}") String password;
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
  var first=java.util.concurrent.CompletableFuture.supplyAsync(()->call(resident,path()+"/billing/mock-pay",Map.of("billId",bill)));
  var second=java.util.concurrent.CompletableFuture.supplyAsync(()->call(resident,path()+"/billing/mock-pay",Map.of("billId",bill)));
  ok(first.join());ok(second.join());
  var paid=ok(call(resident,path()+"/billing/mock-pay",Map.of("billId",bill,"amountFen",1)));
  assertEquals("MOCK_PAID",paid.path("bill").path("status").asText());assertEquals(13000,paid.path("payments").get(0).path("amountFen").asLong());
  ok(call(resident,path()+"/billing/mock-pay",Map.of("billId",bill)));
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
