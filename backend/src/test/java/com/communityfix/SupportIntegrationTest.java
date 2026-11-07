package com.communityfix;

import com.communityfix.common.*;
import com.communityfix.module.support.*;
import com.communityfix.module.ai.AiGuard;
import com.fasterxml.jackson.databind.*;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.test.context.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={"communityfix.wechat.order-template-id=","communityfix.support.jobs-enabled=false","communityfix.ai.api-key=support-test-key","communityfix.ai.model=support-test"})
@EnabledIfEnvironmentVariable(named="CF_INTEGRATION",matches="true")
class SupportIntegrationTest {
 static AtomicReference<String> captured=new AtomicReference<>(""),mode=new AtomicReference<>("valid"),citation=new AtomicReference<>("0");
 static HttpServer provider=start();static String endpoint="http://127.0.0.1:"+provider.getAddress().getPort()+"/chat";
 static HttpServer start(){try{var s=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);s.setExecutor(Executors.newCachedThreadPool(r->{Thread t=new Thread(r);t.setDaemon(true);return t;}));s.createContext("/chat",e->{captured.set(new String(e.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));if(mode.get().equals("slow"))try{Thread.sleep(11000);}catch(InterruptedException ignored){}var mapper=new ObjectMapper();String content=mapper.writeValueAsString(mode.get().equals("chat") ? Map.of("kind","CHAT","answer","我是社区平台的 AI 助手，也可以陪你聊日常生活。","citations",List.of()) : mode.get().equals("clarify") ? Map.of("kind","CHAT","answer","能说说灯是在哪个房间坏的吗？","citations",List.of()) : Map.of("kind","KNOWLEDGE","answer","请先提交房屋绑定申请，等待物业审核。","citations",List.of(mode.get().equals("bad")?"9999999":citation.get())));byte[] bytes=mapper.writeValueAsBytes(Map.of("choices",List.of(Map.of("message",Map.of("content",content)))));try{e.sendResponseHeaders(mode.get().equals("error")?503:200,bytes.length);e.getResponseBody().write(bytes);}finally{e.close();}});s.start();return s;}catch(Exception e){throw new ExceptionInInitializerError(e);}}
 @DynamicPropertySource static void config(DynamicPropertyRegistry r){r.add("communityfix.ai.endpoint",()->endpoint);}
 @AfterAll static void stop(){provider.stop(0);}
 @Autowired TestRestTemplate rest;@Autowired Db db;@Autowired SupportJobs jobs;@Autowired SupportAi ai;@Autowired SupportService service;
 @Value("${communityfix.bootstrap-password}")String password;
 String user,root,property,other;long uid,community,room;
 ResponseEntity<JsonNode> call(String token,HttpMethod method,String path,Object body){var h=new HttpHeaders();h.setContentType(MediaType.APPLICATION_JSON);if(token!=null)h.setBearerAuth(token);return rest.exchange("/api"+path,method,new HttpEntity<>(body,h),JsonNode.class);}
 JsonNode ok(ResponseEntity<JsonNode> r){assertEquals(200,r.getStatusCode().value(),()->String.valueOf(r.getBody()));return r.getBody().path("data");}
 String login(String name){return ok(call(null,HttpMethod.POST,"/auth/login",Map.of("username",name,"password",password))).path("token").asText();}
 @BeforeEach void setup(){mode.set("valid");captured.set("");root=login("admin");property=login("property");other=login("property2");community=Db.id(db.required("SELECT a.community_id FROM admin_community a JOIN sys_user u ON u.id=a.user_id WHERE u.username='property' LIMIT 1"),"communityId");room=Db.id(db.required("SELECT r.room_id FROM resident_room_binding r JOIN sys_user u ON u.id=r.user_id WHERE u.username='resident' AND r.status='APPROVED' LIMIT 1"),"roomId");var r=ok(call(null,HttpMethod.POST,"/auth/register",Map.of("username","sup"+UUID.randomUUID().toString().replace("-",""),"nickname","客服测试用户","password",password)));user=r.path("token").asText();uid=r.path("user").path("id").asLong();ok(call(user,HttpMethod.POST,"/room-bindings",Map.of("roomId",room)));db.execute("DELETE FROM ai_circuit_state WHERE provider_key=?",AiGuard.providerKey(endpoint,"support-test"));}
 JsonNode create(){return ok(call(user,HttpMethod.POST,"/support/sessions",Map.of("role","RESIDENT","communityId",community)));}
 JsonNode action(String token,JsonNode s,String a){return ok(call(token,HttpMethod.POST,"/support/sessions/"+s.path("id").asText()+"/"+a,Map.of("version",s.path("version").asInt())));}
 JsonNode send(String token,JsonNode s,String text){return ok(call(token,HttpMethod.POST,"/support/sessions/"+s.path("id").asText()+"/messages",Map.of("clientId",UUID.randomUUID().toString(),"content",text)));}
 JsonNode events(String token,JsonNode s){return ok(call(token,HttpMethod.GET,"/support/sessions/"+s.path("id").asText()+"/events?wait=false",null));}
 void online(String token){ok(call(token,HttpMethod.POST,"/support/heartbeat",Map.of("online",true)));}
 JsonNode knowledge(String token,Long c,String key){var b=new HashMap<String,Object>(Map.of("title","绑定问答","keywords",key,"question","如何申请房屋绑定","answer","提交申请，等待物业审核。","audience","RESIDENT","status","PUBLISHED","version",0));b.put("communityId",c);return ok(call(token,HttpMethod.POST,"/support/knowledge",b));}
 Map<String,Object> claim(long sid){for(int i=0;i<100;i++){var j=jobs.claim();if(j==null)throw new AssertionError("No job");if(Db.id(j,"sessionId")==sid)return j;jobs.finish(j,"测试降级",null,"TEST",0);}throw new AssertionError("Job missing");}
 @Test void humanConversationAndNotifications(){var s=create();s=action(user,s,"handoff");send(user,s,"离线留言");online(property);s=action(property,s,"claim");send(property,s,"物业已收到，请稍等");assertTrue(events(user,s).toString().contains("物业已收到"));assertTrue(ok(call(user,HttpMethod.GET,"/notifications",null)).toString().contains("supportSessionId"));s=action(property,s,"close");assertEquals("CLOSED",s.path("status").asText());assertEquals(400,call(user,HttpMethod.POST,"/support/sessions/"+s.path("id").asText()+"/messages",Map.of("clientId",UUID.randomUUID().toString(),"content","关闭后发送")).getStatusCode().value());}
 @Test void isolationAndPlatformFallback(){var s=create();assertEquals(404,call(other,HttpMethod.GET,"/support/sessions/"+s.path("id").asText(),null).getStatusCode().value());assertEquals(404,call(login("resident2"),HttpMethod.GET,"/support/sessions/"+s.path("id").asText()+"/events?wait=false",null).getStatusCode().value());var p=ok(call(user,HttpMethod.POST,"/support/sessions",Map.of("role","RESIDENT")));assertEquals(404,call(property,HttpMethod.GET,"/support/sessions/"+p.path("id").asText(),null).getStatusCode().value());p=action(user,p,"handoff");online(root);assertEquals("HUMAN",action(root,p,"claim").path("status").asText());}
 @Test void duplicateMessagesAndVersionConflicts(){var s=action(user,create(),"handoff");var body=Map.of("clientId",UUID.randomUUID().toString(),"content","同一留言");String path="/support/sessions/"+s.path("id").asText()+"/messages";assertEquals(ok(call(user,HttpMethod.POST,path,body)).path("id"),ok(call(user,HttpMethod.POST,path,body)).path("id"));assertEquals(409,call(user,HttpMethod.POST,path,Map.of("clientId",body.get("clientId"),"content","不同内容")).getStatusCode().value());assertEquals(409,call(user,HttpMethod.POST,"/support/sessions/"+s.path("id").asText()+"/cancel-wait",Map.of("version",0)).getStatusCode().value());assertEquals("AI",action(user,s,"cancel-wait").path("status").asText());}
 @Test void competingClaimsAndAdminTakeover()throws Exception{var s=action(user,create(),"handoff");online(property);online(root);String path="/support/sessions/"+s.path("id").asText()+"/claim";var pool=Executors.newFixedThreadPool(2);try{var f1=pool.submit(()->call(property,HttpMethod.POST,path,Map.of("version",1)).getStatusCode().value());var f2=pool.submit(()->call(root,HttpMethod.POST,path,Map.of("version",1)).getStatusCode().value());assertEquals(Set.of(200,409),Set.of(f1.get(),f2.get()));}finally{pool.shutdownNow();}s=ok(call(root,HttpMethod.GET,"/support/sessions/"+s.path("id").asText(),null)).path("session");s=action(root,s,"takeover");assertEquals(403,call(property,HttpMethod.POST,"/support/sessions/"+s.path("id").asText()+"/messages",Map.of("clientId",UUID.randomUUID().toString(),"content","旧客服回复")).getStatusCode().value());}
 @Test void offlineAndRevokedAuthorizationRequeue(){var s=action(user,create(),"handoff");online(property);s=action(property,s,"claim");ok(call(property,HttpMethod.POST,"/support/heartbeat",Map.of("online",false)));service.maintain();assertEquals("WAITING",events(user,s).path("session").path("status").asText());online(property);s=action(property,events(user,s).path("session"),"claim");long pid=Db.id(db.required("SELECT id FROM sys_user WHERE username='property'"),"id");try{db.execute("DELETE FROM admin_community WHERE user_id=? AND community_id=?",pid,community);service.maintain();assertEquals(404,call(property,HttpMethod.GET,"/support/sessions/"+s.path("id").asText()+"/events?wait=false",null).getStatusCode().value());assertEquals("WAITING",events(user,s).path("session").path("status").asText());}finally{db.execute("INSERT IGNORE INTO admin_community(user_id,community_id) VALUES (?,?)",pid,community);}}
 @Test void longPollRevalidatesDisabledAccount()throws Exception{var s=create();long cursor=events(user,s).path("cursor").asLong();var pool=Executors.newSingleThreadExecutor();try{String path="/support/sessions/"+s.path("id").asText()+"/events?after="+cursor;var f=pool.submit(()->call(user,HttpMethod.GET,path,null));Thread.sleep(600);db.execute("UPDATE sys_user SET status='DISABLED',token_version=token_version+1 WHERE id=?",uid);assertEquals(401,f.get(4,TimeUnit.SECONDS).getStatusCode().value());}finally{pool.shutdownNow();}}
 @Test void knowledgeScopeAndPublication(){var k=knowledge(property,community,"绑定");var body=new HashMap<String,Object>(Map.of("communityId",community,"title","本地测试问答","keywords","绑定","question","问题","answer","答案","audience","ALL","status","DRAFT","version",0));assertEquals(403,call(other,HttpMethod.PATCH,"/support/knowledge/"+k.path("id").asText(),body).getStatusCode().value());for(var row:ok(call(login("resident2"),HttpMethod.GET,"/support/knowledge",null)))assertNotEquals(k.path("id"),row.path("id"));assertTrue(ok(call(user,HttpMethod.GET,"/support/knowledge?communityId="+community,null)).toString().contains("提交申请"));ok(call(property,HttpMethod.PATCH,"/support/knowledge/"+k.path("id").asText(),body));for(var row:ok(call(user,HttpMethod.GET,"/support/knowledge?communityId="+community,null)))assertNotEquals(k.path("id"),row.path("id"));}
 @Test void workerRelationsAndOrderPermissions(){String w=login("worker");var s=ok(call(w,HttpMethod.POST,"/support/sessions",Map.of("role","WORKER","communityId",community)));assertEquals(200,call(property,HttpMethod.GET,"/support/sessions/"+s.path("id").asText(),null).getStatusCode().value());long wid=Db.id(db.required("SELECT w.id FROM worker_profile w JOIN sys_user u ON u.id=w.user_id WHERE u.username='worker'"),"id");var owner=db.required("SELECT owner_community_id FROM worker_profile WHERE id=?",wid).get("ownerCommunityId");try{db.execute("DELETE FROM worker_service_community WHERE worker_id=? AND community_id=?",wid,community);db.execute("UPDATE worker_profile SET owner_community_id=NULL WHERE id=?",wid);assertEquals(404,call(w,HttpMethod.GET,"/support/sessions/"+s.path("id").asText(),null).getStatusCode().value());}finally{db.execute("INSERT IGNORE INTO worker_service_community(worker_id,community_id) VALUES (?,?)",wid,community);db.execute("UPDATE worker_profile SET owner_community_id=? WHERE id=?",owner,wid);}var h=new HttpHeaders();h.setBearerAuth(login("resident"));h.set("Idempotency-Key",UUID.randomUUID().toString());var order=ok(rest.exchange("/api/repair/orders",HttpMethod.POST,new HttpEntity<>(Map.of("roomId",room,"categoryId",1,"title","客服关联工单","description","测试权限","priority","NORMAL","attachmentIds",List.of()),h),JsonNode.class));assertEquals(404,call(user,HttpMethod.POST,"/support/sessions",Map.of("role","RESIDENT","orderId",order.path("id").asText())).getStatusCode().value());var mine=ok(call(login("resident"),HttpMethod.POST,"/support/sessions",Map.of("role","RESIDENT","orderId",order.path("id").asText())));assertEquals(order.path("id"),events(login("resident"),mine).path("order").path("id"));}
 @Test void validAiUsesSourcesAndRedactsSecrets(){var k=knowledge(root,null,"绑定");citation.set(k.path("id").asText());var s=create();send(user,s,"绑定：手机号13812345678 密码=SecretPass123 地址：青禾路123号602室");ai.process(claim(s.path("id").asLong()));String prompt=captured.get();assertFalse(prompt.contains("13812345678"));assertFalse(prompt.contains("SecretPass123"));assertFalse(prompt.contains("青禾路123"));assertTrue(events(user,s).toString().contains("AI"));assertEquals("AI",events(user,s).path("messages").get(2).path("metadata").path("source").asText());}
 @Test void invalidCitationAndTimeoutFallBack(){var k=knowledge(root,null,"专用超时词");citation.set(k.path("id").asText());var s=create();mode.set("bad");send(user,s,"专用超时词");ai.process(claim(s.path("id").asLong()));assertEquals("AI_INVALID_RESPONSE",events(user,s).path("messages").get(2).path("metadata").path("errorCode").asText());mode.set("slow");send(user,s,"专用超时词");ai.process(claim(s.path("id").asLong()));assertEquals("FALLBACK",events(user,s).path("messages").get(4).path("metadata").path("source").asText());}
 @Test void lateAiAndLeaseTakeoverAreFenced(){var s=create();send(user,s,"绑定");var first=claim(s.path("id").asLong());db.execute("UPDATE support_ai_job SET locked_until=DATE_SUB(UTC_TIMESTAMP(3),INTERVAL 1 SECOND) WHERE id=?",first.get("id"));var second=jobs.claim();assertEquals(first.get("id"),second.get("id"));jobs.finish(first,"不应出现旧租约",null,null,1);assertFalse(events(user,s).toString().contains("不应出现旧租约"));s=action(user,s,"handoff");jobs.finish(second,"不应出现迟到回复",null,null,1);assertFalse(events(user,s).toString().contains("不应出现迟到回复"));}
 @Test void greetingUsesProviderAndRetriesProduceOneReply(){
  var s=create();mode.set("chat");
  String path="/support/sessions/"+s.path("id").asText()+"/messages";
  var body=Map.of("clientId",UUID.randomUUID().toString(),"content","你好！");
  assertEquals(ok(call(user,HttpMethod.POST,path,body)).path("id"),ok(call(user,HttpMethod.POST,path,body)).path("id"));
  ai.process(claim(s.path("id").asLong()));
  var messages=events(user,s).path("messages");
  assertEquals(3,messages.size());assertEquals("AI",messages.get(2).path("metadata").path("source").asText());
  assertEquals("我是社区平台的 AI 助手，也可以陪你聊日常生活。",messages.get(2).path("content").asText());
  assertTrue(captured.get().contains("你好"));
  send(user,s,"你是谁");ai.process(claim(s.path("id").asLong()));
  assertTrue(captured.get().contains("我是社区平台的 AI 助手"));
  assertTrue(captured.get().contains("你是谁"));
 }
 @Test void noKnowledgeFollowUpUsesOnlyThisChatAndAcceptsUncitedClarification(){
  var s=create();mode.set("clarify");
  send(user,s,"家里的灯坏了");ai.process(claim(s.path("id").asLong()));
  send(user,s,"那我要怎么提交？");ai.process(claim(s.path("id").asLong()));
  var last=events(user,s).path("messages").get(4);
  assertEquals("能说说灯是在哪个房间坏的吗？",last.path("content").asText());
  assertTrue(last.path("metadata").path("citations").isEmpty());
  assertTrue(captured.get().contains("家里的灯坏了"));assertTrue(captured.get().contains("那我要怎么提交"));
 }
 @Test void freeChatHasNoKnowledgeGateAndNoTemplateReplacement(){
  mode.set("chat");var s=create();var separate=create();
  send(user,separate,"只在另一个会话中的暗号");
  ai.process(claim(separate.path("id").asLong()));
  send(user,s,"我喜欢散步，周末有什么轻松的活动？");
  ai.process(claim(s.path("id").asLong()));
  assertEquals("我是社区平台的 AI 助手，也可以陪你聊日常生活。",events(user,s).path("messages").get(2).path("content").asText());
  assertFalse(captured.get().contains("只在另一个会话中的暗号"));
 }
 @Test void missingEvidenceStillRejectsAnInventedKnowledgeAnswer(){
  var s=create();citation.set("9999999");
  send(user,s,"你好，请问具体要多少钱？");ai.process(claim(s.path("id").asLong()));
  var last=events(user,s).path("messages").get(2);
  assertEquals("AI_INVALID_RESPONSE",last.path("metadata").path("errorCode").asText());
  assertTrue(last.path("content").asText().contains("暂时不可用"));
  assertTrue(last.path("metadata").path("citations").isEmpty());
 }
 @Test void rateLimitDoesNotBlockHumanMessages(){var s=create();for(int i=0;i<5;i++)send(user,s,"未命中问题"+i);assertEquals(429,call(user,HttpMethod.POST,"/support/sessions/"+s.path("id").asText()+"/messages",Map.of("clientId",UUID.randomUUID().toString(),"content","超限")).getStatusCode().value());s=action(user,s,"handoff");send(user,s,"人工留言不占AI额度");}
 @Test void retentionAndIdleClose(){var s=create();long id=s.path("id").asLong();db.execute("UPDATE support_session SET updated_at=DATE_SUB(UTC_TIMESTAMP(3),INTERVAL 8 DAY) WHERE id=?",id);service.maintain();assertEquals("CLOSED",events(user,s).path("session").path("status").asText());db.execute("UPDATE support_session SET closed_at=DATE_SUB(UTC_TIMESTAMP(3),INTERVAL 91 DAY) WHERE id=?",id);service.maintain();assertEquals(0,db.count("SELECT COUNT(*) FROM support_message WHERE session_id=?",id));assertEquals(404,call(user,HttpMethod.GET,"/support/sessions/"+id,null).getStatusCode().value());}
}
