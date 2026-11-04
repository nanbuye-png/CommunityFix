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

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={"communityfix.wechat.order-template-id=","communityfix.ai.endpoint="})
@EnabledIfEnvironmentVariable(named="CF_INTEGRATION",matches="true")
class WorkerManagementTest {
 @Autowired TestRestTemplate rest; @Autowired Db db;
 @Value("${communityfix.bootstrap-password}") String password;
 ResponseEntity<JsonNode> call(String token,HttpMethod method,String path,Object body){
  var headers=new HttpHeaders();headers.setContentType(MediaType.APPLICATION_JSON);if(token!=null)headers.setBearerAuth(token);
  return rest.exchange("/api"+path,method,new HttpEntity<>(body,headers),JsonNode.class);
 }
 JsonNode ok(ResponseEntity<JsonNode> r){assertEquals(200,r.getStatusCode().value(),()->String.valueOf(r.getBody()));return r.getBody().path("data");}
 String login(String name){return ok(call(null,HttpMethod.POST,"/auth/login",Map.of("username",name,"password",password))).path("token").asText();}
 long community(String username){return Db.id(db.required("SELECT a.community_id FROM admin_community a JOIN sys_user u ON u.id=a.user_id WHERE u.username=?",username),"communityId");}
 Map<String,Object> body(long owner){return new HashMap<>(Map.of("username","w"+UUID.randomUUID().toString().replace("-",""),"password",password,"realName","测试维修人员","ownerCommunityId",owner,"communityIds",List.of(owner),"categoryIds",List.of(1),"maxActiveOrders",5));}
 @Test void scopedCreationSharingAndOwnership(){
  String admin=login("admin"),property=login("property"),other=login("property2");long a=community("property"),b=community("property2");
  var input=body(a);var created=ok(call(property,HttpMethod.POST,"/workers/accounts",input));String id=created.path("id").asText(),uid=created.path("userId").asText();
  String worker=login(input.get("username").toString());
  assertEquals(404,call(other,HttpMethod.PATCH,"/workers/"+id+"/account",Map.of("password",password)).getStatusCode().value());
  assertEquals(403,call(property,HttpMethod.GET,"/system/users",null).getStatusCode().value());
  assertEquals(403,call(property,HttpMethod.GET,"/workers/account-candidates",null).getStatusCode().value());
  ok(call(admin,HttpMethod.PATCH,"/workers/"+id+"/services",Map.of("communityIds",List.of(a,b))));
  assertEquals(403,call(other,HttpMethod.PATCH,"/workers/"+id+"/account",Map.of("password",password)).getStatusCode().value());
  ok(call(property,HttpMethod.PATCH,"/workers/"+id+"/services",Map.of("communityIds",List.of(a))));
  assertEquals(2,db.count("SELECT COUNT(*) FROM worker_service_community WHERE worker_id=?",id));
  assertEquals(403,call(property,HttpMethod.PATCH,"/workers/"+id+"/owner",Map.of("communityId",b)).getStatusCode().value());
  ok(call(admin,HttpMethod.PATCH,"/workers/"+id+"/owner",Map.of("communityId",b)));
  assertEquals(403,call(property,HttpMethod.PATCH,"/workers/"+id,Map.of("maxActiveOrders",6)).getStatusCode().value());
  ok(call(other,HttpMethod.PATCH,"/workers/"+id+"/account",Map.of("status","DISABLED")));
  assertEquals(401,call(worker,HttpMethod.GET,"/auth/me",null).getStatusCode().value());
  assertEquals("DISABLED",db.required("SELECT status FROM sys_user WHERE id=?",uid).get("status"));
  ok(call(property,HttpMethod.PATCH,"/workers/"+id+"/services",Map.of("communityIds",List.of())));
  assertEquals(1,db.count("SELECT COUNT(*) FROM worker_service_community WHERE worker_id=?",id));
 }
 @Test void invalidCreationAndDuplicateDoNotLeavePartialRecords(){
  String p=login("property");long a=community("property"),b=community("property2");long before=db.count("SELECT COUNT(*) FROM sys_user");
  var input=body(a);input.put("communityIds",List.of(a,b));
  assertEquals(403,call(p,HttpMethod.POST,"/workers/accounts",input).getStatusCode().value());
  input.put("communityIds",List.of(a));input.put("categoryIds",List.of(999999));
  assertEquals(400,call(p,HttpMethod.POST,"/workers/accounts",input).getStatusCode().value());
  input.put("categoryIds",List.of(1));input.put("password","short");
  assertEquals(400,call(p,HttpMethod.POST,"/workers/accounts",input).getStatusCode().value());
  assertEquals(before,db.count("SELECT COUNT(*) FROM sys_user"));
  input.put("password",password);ok(call(p,HttpMethod.POST,"/workers/accounts",input));
  assertEquals(409,call(p,HttpMethod.POST,"/workers/accounts",input).getStatusCode().value());
  assertEquals(before+1,db.count("SELECT COUNT(*) FROM sys_user"));
 }
 @Test void activeAssignmentsBlockServiceRemovalAndRevokedOwnerLosesAccess(){
  String root=login("admin"),p=login("property");long a=community("property"),b=community("property2");
  var created=ok(call(p,HttpMethod.POST,"/workers/accounts",body(a)));String id=created.path("id").asText();
  long room=Db.id(db.required("SELECT room_id FROM resident_room_binding r JOIN sys_user u ON u.id=r.user_id WHERE u.username='resident' AND r.status='APPROVED' LIMIT 1"),"roomId");
  String resident=login("resident");
  var headers=new HttpHeaders();headers.setContentType(MediaType.APPLICATION_JSON);headers.setBearerAuth(resident);headers.set("Idempotency-Key",UUID.randomUUID().toString());
  var order=ok(rest.exchange("/api/repair/orders",HttpMethod.POST,new HttpEntity<>(Map.of("facilityScope","PUBLIC","locationText","楼道公共设施测试位置","roomId",room,"categoryId",1,"title","维修人员归属测试","description","检查服务关系","priority","NORMAL","attachmentIds",List.of()),headers),JsonNode.class));
  String oid=order.path("id").asText();
  headers.setBearerAuth(p);headers.set("Idempotency-Key",UUID.randomUUID().toString());
  order=ok(rest.exchange("/api/repair/orders/"+oid+"/review",HttpMethod.POST,new HttpEntity<>(Map.of("version",order.path("version").asInt(),"approved",true),headers),JsonNode.class));
  headers.set("Idempotency-Key",UUID.randomUUID().toString());
  ok(rest.exchange("/api/repair/orders/"+oid+"/assign",HttpMethod.POST,new HttpEntity<>(Map.of("version",order.path("version").asInt(),"workerId",id),headers),JsonNode.class));
  ok(call(root,HttpMethod.PATCH,"/workers/"+id+"/services",Map.of("communityIds",List.of(a,b))));
  ok(call(root,HttpMethod.PATCH,"/workers/"+id+"/owner",Map.of("communityId",b)));
  assertEquals(400,call(p,HttpMethod.PATCH,"/workers/"+id+"/services",Map.of("communityIds",List.of())).getStatusCode().value());
  long otherId=Db.id(db.required("SELECT id FROM sys_user WHERE username='property2'"),"id");
  try{
   ok(call(root,HttpMethod.DELETE,"/system/admin-communities/"+otherId+"/"+b,null));
   assertEquals(404,call(login("property2"),HttpMethod.PATCH,"/workers/"+id+"/account",Map.of("realName","越权")).getStatusCode().value());
  }finally{ok(call(root,HttpMethod.POST,"/system/admin-communities",Map.of("userId",otherId,"communityId",b)));}
 }
 @Test void candidatesFilterAndLinkingAreRestricted(){
  String root=login("admin");long a=community("property");String name="link"+UUID.randomUUID().toString().replace("-","");
  var user=ok(call(root,HttpMethod.POST,"/system/users",Map.of("username",name,"password",password,"nickname","同名师傅","roles",List.of("WORKER"))));
  var list=ok(call(root,HttpMethod.GET,"/workers/account-candidates?q="+name,null));assertEquals(1,list.path("total").asInt());
  var profile=Map.of("userId",user.path("id").asText(),"realName","同名师傅","communityIds",List.of(a),"categoryIds",List.of(1),"maxActiveOrders",5);
  assertEquals(403,call(login("property"),HttpMethod.POST,"/workers",profile).getStatusCode().value());
  ok(call(root,HttpMethod.POST,"/workers",profile));
  assertEquals(0,ok(call(root,HttpMethod.GET,"/workers/account-candidates?q="+name,null)).path("total").asInt());
  assertEquals(409,call(root,HttpMethod.POST,"/workers",profile).getStatusCode().value());
 }
 @Test void residentsAndWorkersCanChooseTheirOwnRegistration(){
  long a=community("property"),b=community("property2");String pwd=password;
  String residentName="resident"+UUID.randomUUID().toString().replace("-","");
  var resident=ok(call(null,HttpMethod.POST,"/auth/register",Map.of("username",residentName,"password",pwd,"nickname","自助居民","role","RESIDENT")));
  assertTrue(resident.path("roles").toString().contains("RESIDENT"));assertFalse(resident.path("roles").toString().contains("WORKER"));
  String workerName="worker"+UUID.randomUUID().toString().replace("-","");
  var worker=ok(call(null,HttpMethod.POST,"/auth/register",Map.of("username",workerName,"password",pwd,"nickname","自助师傅","role","WORKER","communityId",a)));
  assertTrue(worker.path("roles").toString().contains("WORKER"));assertFalse(worker.path("roles").toString().contains("ADMIN"));
  String id=db.required("SELECT w.id FROM worker_profile w JOIN sys_user u ON u.id=w.user_id WHERE u.username=?",workerName).get("id").toString();
  assertEquals("DISABLED",db.required("SELECT work_status FROM worker_profile WHERE id=?",id).get("workStatus"));
  assertEquals(403,call(worker.path("token").asText(),HttpMethod.GET,"/workers",null).getStatusCode().value());
  assertEquals(404,call(login("property2"),HttpMethod.PATCH,"/workers/"+id,Map.of("workStatus","AVAILABLE")).getStatusCode().value());
  assertEquals(200,call(login("property"),HttpMethod.PATCH,"/workers/"+id,Map.of("workStatus","AVAILABLE","categoryIds",List.of(1))).getStatusCode().value());
  assertEquals("AVAILABLE",db.required("SELECT work_status FROM worker_profile WHERE id=?",id).get("workStatus"));
  long before=db.count("SELECT COUNT(*) FROM sys_user");
  assertEquals(400,call(null,HttpMethod.POST,"/auth/register",Map.of("username","badworker","password",pwd,"nickname","错误小区","role","WORKER","communityId",b+999999)).getStatusCode().value());
  assertEquals(before,db.count("SELECT COUNT(*) FROM sys_user"));
  assertEquals(400,call(null,HttpMethod.POST,"/auth/register",Map.of("username","badrole","password",pwd,"nickname","错误角色","role","SUPER_ADMIN")).getStatusCode().value());
 }
 @Test void propertyCanManageOnlyResidentsBoundInItsCommunity(){
  String property=login("property"),other=login("property2");long room=Db.id(db.required("SELECT room_id FROM resident_room_binding br JOIN sys_user u ON u.id=br.user_id WHERE u.username='resident' AND br.status='APPROVED' LIMIT 1"),"roomId");
  var resident=ok(call(null,HttpMethod.POST,"/auth/register",Map.of("username","bound"+UUID.randomUUID().toString().replace("-",""),"password",password,"nickname","待绑定居民","role","RESIDENT")));
  String uid=resident.path("user").path("id").asText(),token=resident.path("token").asText();
  assertEquals(404,call(property,HttpMethod.PATCH,"/system/community-users/"+uid,Map.of("nickname","越权")).getStatusCode().value());
  ok(call(token,HttpMethod.POST,"/room-bindings",Map.of("facilityScope","PUBLIC","locationText","楼道公共设施测试位置","roomId",room)));
  assertEquals(404,call(other,HttpMethod.PATCH,"/system/community-users/"+uid,Map.of("nickname","越权")).getStatusCode().value());
  assertEquals(403,call(property,HttpMethod.PATCH,"/system/community-users/"+uid,Map.of("roles",List.of("ADMIN"))).getStatusCode().value());
  ok(call(property,HttpMethod.PATCH,"/system/community-users/"+uid,Map.of("nickname","物业确认居民")));
  assertEquals("物业确认居民",db.required("SELECT nickname FROM sys_user WHERE id=?",uid).get("nickname"));
 }
}
