package com.communityfix;

import com.communityfix.common.Db;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={"communityfix.wechat.order-template-id=","communityfix.ai.endpoint="})
@EnabledIfEnvironmentVariable(named="CF_INTEGRATION",matches="true")
class WorkflowIntegrationTest {
    @Autowired TestRestTemplate rest;@Autowired Db db;@Autowired ObjectMapper json;
    @Value("${communityfix.bootstrap-password}") String password;
    @Autowired com.communityfix.module.dispatch.SlaService sla;
    private String admin,worker,resident;private long room,workerId;
    @BeforeEach void fixture(){admin=login("property");worker=login("worker");resident=login("resident");room=Db.id(db.required("SELECT room_id FROM resident_room_binding b JOIN sys_user u ON u.id=b.user_id WHERE u.username='resident' AND b.status='APPROVED' LIMIT 1"),"roomId");workerId=Db.id(db.required("SELECT w.id FROM worker_profile w JOIN sys_user u ON u.id=w.user_id WHERE u.username='worker'"),"id");}
    private String login(String name){return call(null,HttpMethod.POST,"/api/auth/login",Map.of("username",name,"password",password),null).getBody().path("data").path("token").asText();}
    private ResponseEntity<JsonNode> call(String token,HttpMethod method,String path,Object body,String key){HttpHeaders headers=new HttpHeaders();headers.setContentType(MediaType.APPLICATION_JSON);if(token!=null)headers.setBearerAuth(token);if(key!=null)headers.set("Idempotency-Key",key);return rest.exchange(path,method,new HttpEntity<>(body,headers),JsonNode.class);}
    private JsonNode ok(ResponseEntity<JsonNode> response){assertEquals(200,response.getStatusCode().value(),()->Objects.toString(response.getBody()));assertNotNull(response.getBody());assertFalse(response.getBody().path("requestId").asText().isBlank());return response.getBody().get("data");}
    private Map<String,Object> createBody(){return Map.of("facilityScope","PUBLIC","locationText","楼道公共设施测试位置","roomId",Long.toString(room),"categoryId","1","title","集成测试水管漏水","description","厨房水管连接处漏水","priority","NORMAL","attachmentIds",List.of());}
    private JsonNode create(){return ok(call(resident,HttpMethod.POST,"/api/repair/orders",createBody(),UUID.randomUUID().toString()));}
    private JsonNode action(String token,JsonNode order,String action,Map<String,Object> extra){Map<String,Object> body=new HashMap<>(extra);body.put("version",order.path("version").asInt());return ok(call(token,HttpMethod.POST,"/api/repair/orders/"+order.path("id").asText()+"/"+action,body,UUID.randomUUID().toString()));}
    @Test void fullWorkflowWithReworkEvaluationAndComplaint(){
        JsonNode order=create();String id=order.path("id").asText();assertTrue(order.path("id").isTextual());
        order=action(admin,order,"review",Map.of("approved",true));order=action(admin,order,"assign",Map.of("workerId",Long.toString(workerId)));order=action(worker,order,"accept",Map.of());assertEquals("ACCEPTED",order.path("status").asText());
        assertEquals(409,call(worker,HttpMethod.POST,"/api/repair/orders/"+id+"/finish",Map.of("version",order.path("version").asInt(),"content","尚未开始"),UUID.randomUUID().toString()).getStatusCode().value());
        order=action(worker,order,"start",Map.of());order=action(worker,order,"finish",Map.of("content","更换连接件"));order=action(resident,order,"rework",Map.of("reason","仍有渗漏"));assertEquals(2,order.path("roundNo").asInt());assertTrue(order.path("workerId").isNull());
        order=action(admin,order,"assign",Map.of("workerId",Long.toString(workerId)));order=action(worker,order,"accept",Map.of());order=action(worker,order,"start",Map.of());order=action(worker,order,"finish",Map.of("content","重新密封并测试无渗漏"));order=action(resident,order,"confirm",Map.of());assertEquals("CLOSED",order.path("status").asText());
        assertEquals(2,db.count("SELECT COUNT(*) FROM repair_work_record WHERE order_id=? AND record_type='RESULT'",id));assertEquals(0,db.count("SELECT COUNT(*) FROM repair_evaluation WHERE order_id=?",id));
        ok(call(resident,HttpMethod.POST,"/api/repair/orders/"+id+"/evaluations",Map.of("score",5,"content","处理及时"),UUID.randomUUID().toString()));assertEquals(409,call(resident,HttpMethod.POST,"/api/repair/orders/"+id+"/evaluations",Map.of("score",4),UUID.randomUUID().toString()).getStatusCode().value());
        var complaint=ok(call(resident,HttpMethod.POST,"/api/repair/orders/"+id+"/complaints",Map.of("content","希望下次提前联系"),UUID.randomUUID().toString()));
        String cid=complaint.path("id").asText(),detailPath="/api/complaints/"+cid;
        var detail=ok(call(admin,HttpMethod.GET,detailPath,null,null));
        assertEquals("希望下次提前联系",detail.path("complaint").path("content").asText());
        assertEquals(order.path("orderNo").asText(),detail.path("order").path("orderNo").asText());
        assertEquals(1,detail.path("logs").size());
        assertFalse(detail.path("logs").get(0).path("operatorName").asText().isBlank());
        assertEquals(200,call(resident,HttpMethod.GET,detailPath,null,null).getStatusCode().value());
        for(String other:List.of("resident2","property2"))assertEquals(404,call(login(other),HttpMethod.GET,detailPath,null,null).getStatusCode().value());
        assertEquals(403,call(worker,HttpMethod.GET,detailPath,null,null).getStatusCode().value());
        assertEquals(403,call(login("admin"),HttpMethod.GET,detailPath,null,null).getStatusCode().value());
        assertEquals(409,call(admin,HttpMethod.POST,detailPath+"/process",Map.of("version",0,"status","CLOSED","resolution","不能跳过受理"),null).getStatusCode().value());
        ok(call(admin,HttpMethod.POST,detailPath+"/process",Map.of("version",0,"status","PROCESSING","resolution","已受理"),null));
        assertEquals(409,call(admin,HttpMethod.POST,detailPath+"/process",Map.of("version",0,"status","CLOSED","resolution","旧版本不能关闭"),null).getStatusCode().value());
        ok(call(admin,HttpMethod.POST,detailPath+"/process",Map.of("version",1,"status","CLOSED","resolution","已反馈并改进"),null));
        detail=ok(call(admin,HttpMethod.GET,detailPath,null,null));
        assertEquals("CLOSED",detail.path("complaint").path("status").asText());
        assertEquals("已反馈并改进",detail.path("complaint").path("resolution").asText());
        assertEquals(3,detail.path("logs").size());
        assertEquals(3,ok(call(resident,HttpMethod.GET,detailPath+"/logs",null,null)).size());
    }
    @Test void duplicateRequestsAndDifferentPayloadAreSafe(){String key=UUID.randomUUID().toString();var first=ok(call(resident,HttpMethod.POST,"/api/repair/orders",createBody(),key));var second=ok(call(resident,HttpMethod.POST,"/api/repair/orders",createBody(),key));assertEquals(first.path("id"),second.path("id"));var changed=new HashMap<>(createBody());changed.put("title","不同标题");assertEquals(409,call(resident,HttpMethod.POST,"/api/repair/orders",changed,key).getStatusCode().value());assertEquals(1,db.count("SELECT COUNT(*) FROM repair_order_log WHERE order_id=?",first.path("id").asText()));action(resident,first,"cancel",Map.of("reason","测试结束"));}
    @Test void residentAndCommunityIsolation(){var order=create();String path="/api/repair/orders/"+order.path("id").asText();assertEquals(404,call(login("resident2"),HttpMethod.GET,path,null,null).getStatusCode().value());assertEquals(404,call(login("property2"),HttpMethod.GET,path,null,null).getStatusCode().value());assertEquals(403,call(login("admin"),HttpMethod.GET,path,null,null).getStatusCode().value());assertEquals(403,call(resident,HttpMethod.GET,"/api/system/users",null,null).getStatusCode().value());action(resident,order,"cancel",Map.of("reason","测试结束"));}
    @Test void oldVersionAndUnassignedWorkersCannotAct(){var order=create();String id=order.path("id").asText();order=action(admin,order,"review",Map.of("approved",true));assertEquals(409,call(admin,HttpMethod.POST,"/api/repair/orders/"+id+"/assign",Map.of("version",0,"workerId",Long.toString(workerId)),UUID.randomUUID().toString()).getStatusCode().value());assertEquals(404,call(worker,HttpMethod.POST,"/api/repair/orders/"+id+"/accept",Map.of("version",1),UUID.randomUUID().toString()).getStatusCode().value());action(resident,order,"cancel",Map.of("reason","测试结束"));}
    @Test void concurrentOrdersCannotExceedWorkerCapacity()throws Exception{
        var first=action(admin,create(),"review",Map.of("approved",true));var second=action(admin,create(),"review",Map.of("approved",true));
        int previousCapacity=Db.integer(db.required("SELECT max_active_orders FROM worker_profile WHERE id=?",workerId),"maxActiveOrders");
        long existingActive=db.count("SELECT COUNT(*) FROM repair_order WHERE worker_id=? AND status IN ('ASSIGNED','ACCEPTED','IN_PROGRESS')",workerId);
        db.execute("UPDATE worker_profile SET max_active_orders=? WHERE id=?",existingActive+1,workerId);
        try(var executor=Executors.newFixedThreadPool(2)){
            var gate=new CountDownLatch(1);List<Future<ResponseEntity<JsonNode>>> futures=new ArrayList<>();for(var order:List.of(first,second))futures.add(executor.submit(()->{gate.await();return call(admin,HttpMethod.POST,"/api/repair/orders/"+order.path("id").asText()+"/assign",Map.of("version",order.path("version").asInt(),"workerId",Long.toString(workerId)),UUID.randomUUID().toString());}));gate.countDown();List<Integer> codes=new ArrayList<>();for(var future:futures)codes.add(future.get(20,TimeUnit.SECONDS).getStatusCode().value());Collections.sort(codes);assertEquals(List.of(200,409),codes);assertEquals(existingActive+1,db.count("SELECT COUNT(*) FROM repair_order WHERE worker_id=? AND status IN ('ASSIGNED','ACCEPTED','IN_PROGRESS')",workerId));
        }finally{db.execute("UPDATE worker_profile SET max_active_orders=? WHERE id=?",previousCapacity,workerId);for(var source:List.of(first,second)){var current=ok(call(admin,HttpMethod.GET,"/api/repair/orders/"+source.path("id").asText(),null,null));if(current.path("status").asText().equals("ASSIGNED"))current=action(admin,current,"revoke",Map.of("reason","测试结束"));action(resident,current,"cancel",Map.of("reason","测试结束"));}}
    }
    @Test void unauthorisedAttachmentRollsBackEntireCreation(){long id=db.insert("file_asset",Db.fields("uploader_id",Db.id(db.required("SELECT id FROM sys_user WHERE username='resident2'"),"id"),"object_key","test-"+UUID.randomUUID()+".png","content_type","image/png","size_bytes",10,"expires_at",java.time.Instant.now().plusSeconds(3600)));long before=db.count("SELECT COUNT(*) FROM repair_order");var body=new HashMap<>(createBody());body.put("attachmentIds",List.of(Long.toString(id)));assertEquals(403,call(resident,HttpMethod.POST,"/api/repair/orders",body,UUID.randomUUID().toString()).getStatusCode().value());assertEquals(before,db.count("SELECT COUNT(*) FROM repair_order"));assertEquals(0,db.count("SELECT COUNT(*) FROM repair_order_media WHERE file_id=?",id));}
    @Test void adoptedAiResultLinksOnlyOwnUnusedSuccessfulRecord(){
        long owner=Db.id(db.required("SELECT id FROM sys_user WHERE username='resident'"),"id"),other=Db.id(db.required("SELECT id FROM sys_user WHERE username='resident2'"),"id");
        long own=db.insert("ai_analysis_record",Db.fields("user_id",owner,"request_id",UUID.randomUUID().toString(),"input_text","测试输入","summary","测试建议","status","SUCCESS","duration_ms",1));
        long foreign=db.insert("ai_analysis_record",Db.fields("user_id",other,"request_id",UUID.randomUUID().toString(),"input_text","测试输入","status","SUCCESS","duration_ms",1));
        long fallback=db.insert("ai_analysis_record",Db.fields("user_id",owner,"request_id",UUID.randomUUID().toString(),"input_text","测试输入","status","FALLBACK","duration_ms",1));
        List<JsonNode> created=new ArrayList<>();
        try{
            var body=new HashMap<>(createBody());body.put("analysisId",Long.toString(own));body.put("description","陈女士在3栋602室报修，电话13812345678");
            var order=ok(call(resident,HttpMethod.POST,"/api/repair/orders",body,UUID.randomUUID().toString()));created.add(order);
            var linked=db.required("SELECT order_id,adopted_result FROM ai_analysis_record WHERE id=?",own);
            assertEquals(order.path("id").asLong(),Db.id(linked,"orderId"));
            String retained=linked.get("adoptedResult").toString();for(String secret:List.of("陈女士","13812345678","3栋","602室"))assertFalse(retained.contains(secret));
            for(long analysis:List.of(own,foreign,fallback)){body.put("analysisId",Long.toString(analysis));created.add(ok(call(resident,HttpMethod.POST,"/api/repair/orders",body,UUID.randomUUID().toString())));}
            assertEquals(order.path("id").asLong(),Db.id(db.required("SELECT order_id FROM ai_analysis_record WHERE id=?",own),"orderId"));
            for(long ignored:List.of(foreign,fallback))assertNull(db.required("SELECT order_id FROM ai_analysis_record WHERE id=?",ignored).get("orderId"));
        }finally{for(var order:created)action(resident,order,"cancel",Map.of("reason","AI采纳关联测试结束"));db.execute("DELETE FROM ai_analysis_record WHERE id IN (?,?,?)",own,foreign,fallback);}
    }
    @Test void emptyStatisticsAndAiFallbackRemainUsable(){var stats=ok(call(admin,HttpMethod.GET,"/api/statistics/overview?start=2020-01-01&end=2020-02-01",null,null));assertEquals(0,stats.path("newOrders").asInt());assertTrue(stats.path("averageProcessingSeconds").isNull());var result=ok(call(resident,HttpMethod.POST,"/api/ai/repair-analysis",Map.of("description","水管漏水，联系电话13812345678"),null));assertEquals("MANUAL",result.path("source").asText());assertTrue(result.path("categoryId").isNull());String record=(String)db.required("SELECT input_text FROM ai_analysis_record WHERE id=?",result.path("analysisId").asText()).get("inputText");assertFalse(record.contains("13812345678"));}
    @Test void bindingMustBeReviewedBeforeReporting(){String name="cf"+UUID.randomUUID().toString().replace("-","").substring(0,16);var session=ok(call(null,HttpMethod.POST,"/api/auth/register",Map.of("username",name,"password","integration-pass-2026","nickname","测试居民"),null));String token=session.path("token").asText();assertEquals(400,call(token,HttpMethod.POST,"/api/repair/orders",createBody(),UUID.randomUUID().toString()).getStatusCode().value());var binding=ok(call(token,HttpMethod.POST,"/api/room-bindings",Map.of("facilityScope","PUBLIC","locationText","楼道公共设施测试位置","roomId",Long.toString(room)),null));assertEquals(403,call(login("property2"),HttpMethod.POST,"/api/room-bindings/"+binding.path("id").asText()+"/review",Map.of("approved",true),null).getStatusCode().value());ok(call(admin,HttpMethod.POST,"/api/room-bindings/"+binding.path("id").asText()+"/review",Map.of("approved",true),null));var order=ok(call(token,HttpMethod.POST,"/api/repair/orders",createBody(),UUID.randomUUID().toString()));action(token,order,"cancel",Map.of("reason","测试结束"));}
    @Test void systemAdminCanEditResidentAndMoveWithoutRewritingOldOrders(){
        String name="cf"+UUID.randomUUID().toString().replace("-","").substring(0,16);
        var session=ok(call(null,HttpMethod.POST,"/api/auth/register",Map.of("username",name,"password","integration-pass-2026","nickname","迁居前"),null));
        String token=session.path("token").asText(),userId=session.path("user").path("id").asText(),superAdmin=login("admin");
        var binding=ok(call(token,HttpMethod.POST,"/api/room-bindings",Map.of("facilityScope","PUBLIC","locationText","楼道公共设施测试位置","roomId",Long.toString(room)),null));
        ok(call(admin,HttpMethod.POST,"/api/room-bindings/"+binding.path("id").asText()+"/review",Map.of("approved",true),null));
        var oldOrder=ok(call(token,HttpMethod.POST,"/api/repair/orders",createBody(),UUID.randomUUID().toString()));
        String oldBindingId=binding.path("id").asText();
        assertEquals(403,call(admin,HttpMethod.PATCH,"/api/system/users/"+userId,Map.of("nickname","越权"),null).getStatusCode().value());
        ok(call(superAdmin,HttpMethod.PATCH,"/api/system/users/"+userId,Map.of("nickname","迁居后","phone","13800000000"),null));
        assertEquals("迁居后",db.required("SELECT nickname FROM sys_user WHERE id=?",userId).get("nickname"));
        assertEquals("13800000000",db.required("SELECT phone FROM sys_user WHERE id=?",userId).get("phone"));
        assertEquals(200,call(token,HttpMethod.GET,"/api/auth/me",null,null).getStatusCode().value());
        long newRoom=Db.id(db.required("SELECT r.id FROM room r JOIN unit u ON u.id=r.unit_id WHERE r.room_no='603' AND u.unit_no='1' LIMIT 1"),"id");
        assertEquals(403,call(admin,HttpMethod.POST,"/api/system/users/"+userId+"/move",Map.of("oldBindingId",oldBindingId,"newRoomId",Long.toString(newRoom),"reason","越权"),null).getStatusCode().value());
        var moved=ok(call(superAdmin,HttpMethod.POST,"/api/system/users/"+userId+"/move",Map.of("oldBindingId",oldBindingId,"newRoomId",Long.toString(newRoom),"reason","已核实搬家"),null));
        assertEquals(1,moved.path("addresses").size());
        assertEquals(Long.toString(newRoom),moved.path("addresses").get(0).path("roomId").asText());
        assertEquals("REVOKED",db.required("SELECT status FROM resident_room_binding WHERE id=?",oldBindingId).get("status"));
        assertEquals(4,db.count("SELECT COUNT(*) FROM resident_room_binding_log WHERE binding_id IN (?,?)",oldBindingId,moved.path("newBindingId").asText()));
        assertEquals(Long.toString(room),ok(call(token,HttpMethod.GET,"/api/repair/orders/"+oldOrder.path("id").asText(),null,null)).path("roomId").asText());
        assertEquals(400,call(token,HttpMethod.POST,"/api/repair/orders",createBody(),UUID.randomUUID().toString()).getStatusCode().value());
        var newBody=new HashMap<>(createBody());newBody.put("roomId",Long.toString(newRoom));
        var newOrder=ok(call(token,HttpMethod.POST,"/api/repair/orders",newBody,UUID.randomUUID().toString()));
        action(token,oldOrder,"cancel",Map.of("reason","测试结束"));action(token,newOrder,"cancel",Map.of("reason","测试结束"));
        ok(call(superAdmin,HttpMethod.PATCH,"/api/system/users/"+userId,Map.of("password","new-integration-pass-2026","roles",List.of("RESIDENT")),null));
        assertEquals(401,call(token,HttpMethod.GET,"/api/auth/me",null,null).getStatusCode().value());
        var renewed=ok(call(null,HttpMethod.POST,"/api/auth/login",Map.of("username",name,"password","new-integration-pass-2026"),null));
        assertEquals("RESIDENT",renewed.path("roles").get(0).asText());
    }
    @Test void logoutRevokesExistingJwt(){String token=login("resident2");ok(call(token,HttpMethod.POST,"/api/auth/logout",Map.of(),null));assertEquals(401,call(token,HttpMethod.GET,"/api/auth/me",null,null).getStatusCode().value());}
    @Test void unconfiguredWechatSubscriptionKeepsStationMessagesAndOutboxPrivate(){
        assertEquals("",ok(call(resident,HttpMethod.GET,"/api/notifications/wechat-subscription",null,null)).path("orderTemplateId").asText());
        assertEquals(400,call(resident,HttpMethod.POST,"/api/notifications/wechat-subscription",Map.of("templateId","missing-template"),null).getStatusCode().value());
        assertEquals(403,call(resident,HttpMethod.GET,"/api/notifications/outbox",null,null).getStatusCode().value());
        ok(call(login("admin"),HttpMethod.GET,"/api/notifications/outbox",null,null));
        var order=create();
        assertTrue(db.count("SELECT COUNT(*) FROM notification WHERE order_id=?",order.path("id").asText())>0);
        assertEquals(0,db.count("SELECT COUNT(*) FROM notification_outbox WHERE event_key LIKE ?","order:"+order.path("id").asText()+":%"));
        action(resident,order,"cancel",Map.of("reason","测试结束"));
    }
    @Test void slaScanningDeduplicatesAndStopsAfterCancellation(){var order=create();String id=order.path("id").asText();java.time.Instant now=java.time.Instant.now();db.execute("UPDATE repair_order SET response_started_at=?,response_deadline_at=? WHERE id=?",now.minusSeconds(1300),now.minusSeconds(300),id);sla.inspect(Long.parseLong(id));sla.inspect(Long.parseLong(id));assertEquals(1,db.count("SELECT COUNT(*) FROM sla_warning WHERE order_id=?",id));assertEquals(2,db.required("SELECT level FROM sla_warning WHERE order_id=?",id).get("level"));action(resident,order,"cancel",Map.of("reason","测试结束"));sla.inspect(Long.parseLong(id));assertEquals(1,db.count("SELECT COUNT(*) FROM sla_warning WHERE order_id=?",id));}
    @Test void slaWarningListShowsHighestLevelAndSeparatesCurrentFromHistory(){
        var order=create();String id=order.path("id").asText(),query="/api/sla/warnings?search="+order.path("orderNo").asText();
        java.time.Instant now=java.time.Instant.now();
        db.execute("UPDATE repair_order SET response_started_at=?,response_deadline_at=? WHERE id=?",now.minusSeconds(850),now.plusSeconds(150),id);
        sla.inspect(Long.parseLong(id));
        db.execute("UPDATE repair_order SET response_started_at=?,response_deadline_at=? WHERE id=?",now.minusSeconds(1300),now.minusSeconds(300),id);
        sla.inspect(Long.parseLong(id));
        assertEquals(2,db.count("SELECT COUNT(*) FROM sla_warning WHERE order_id=?",id));
        var current=ok(call(admin,HttpMethod.GET,query,null,null));
        assertEquals(1,current.path("total").asInt());assertEquals(2,current.path("records").get(0).path("level").asInt());
        assertEquals(1,current.path("records").get(0).path("currentlyActive").asInt());
        assertEquals(0,ok(call(admin,HttpMethod.GET,query+"&level=1",null,null)).path("total").asInt());
        assertEquals(0,ok(call(admin,HttpMethod.GET,query+"&phase=COMPLETION",null,null)).path("total").asInt());
        assertEquals(0,ok(call(login("property2"),HttpMethod.GET,query+"&active=false",null,null)).path("total").asInt());
        for(String token:List.of(resident,worker,login("admin")))assertEquals(403,call(token,HttpMethod.GET,query,null,null).getStatusCode().value());
        for(String bad:List.of("&level=4","&phase=INVALID","&page=0","&size=101"))assertEquals(400,call(admin,HttpMethod.GET,query+bad,null,null).getStatusCode().value());
        action(resident,order,"cancel",Map.of("reason","预警列表测试结束"));
        assertEquals(0,ok(call(admin,HttpMethod.GET,query,null,null)).path("total").asInt());
        var history=ok(call(admin,HttpMethod.GET,query+"&active=false&phase=RESPONSE&level=2",null,null));
        assertEquals(1,history.path("total").asInt());assertEquals(0,history.path("records").get(0).path("currentlyActive").asInt());
    }
    @Test void notificationDeliveryManagementEnforcesRoleAndAuditsRetry(){
        long recipient=Db.id(db.required("SELECT id FROM sys_user WHERE username='resident'"),"id");
        String event="delivery-management-test:"+UUID.randomUUID();
        long job=db.insert("notification_outbox",Db.fields("event_key",event,"recipient_id",recipient,"channel","WECHAT","payload","{}","status","FAILED","attempts",5,"last_error","test failure"));
        long other=db.insert("notification_outbox",Db.fields("event_key",event+":other","recipient_id",recipient,"channel","SMS","payload","{}","status","FAILED","attempts",5));
        try{
            String root=login("admin"),retry="/api/notifications/outbox/"+job+"/retry";
            for(String token:List.of(resident,admin,worker)){
                assertEquals(403,call(token,HttpMethod.GET,"/api/notifications/outbox/summary",null,null).getStatusCode().value());
                assertEquals(403,call(token,HttpMethod.POST,retry,Map.of(),null).getStatusCode().value());
            }
            var counts=ok(call(root,HttpMethod.GET,"/api/notifications/outbox/summary",null,null));assertTrue(counts.path("FAILED").asInt()>0);
            var list=ok(call(root,HttpMethod.GET,"/api/notifications/outbox?status=FAILED&size=100",null,null));
            boolean found=false;
            for(var row:list.path("records")){assertFalse(row.has("payload"));assertFalse(row.has("wechatOpenid"));assertNotEquals(Long.toString(other),row.path("id").asText());if(row.path("id").asText().equals(Long.toString(job)))found=true;}
            assertTrue(found);
            assertEquals(400,call(root,HttpMethod.GET,"/api/notifications/outbox?status=UNKNOWN",null,null).getStatusCode().value());
            assertEquals(409,call(root,HttpMethod.POST,"/api/notifications/outbox/"+other+"/retry",Map.of(),null).getStatusCode().value());
            ok(call(root,HttpMethod.POST,retry,Map.of(),null));
            var pending=db.required("SELECT status,attempts,last_error,next_retry_at,locked_until FROM notification_outbox WHERE id=?",job);
            assertEquals("PENDING",pending.get("status"));assertEquals(0,pending.get("attempts"));assertNull(pending.get("lastError"));assertNull(pending.get("nextRetryAt"));assertNull(pending.get("lockedUntil"));
            assertEquals(409,call(root,HttpMethod.POST,retry,Map.of(),null).getStatusCode().value());
            assertEquals(1,db.count("SELECT COUNT(*) FROM sys_operation_log WHERE action='NOTIFICATION_RETRY' AND target_id=?",job));
            assertFalse(((String)db.required("SELECT request_id FROM sys_operation_log WHERE action='NOTIFICATION_RETRY' AND target_id=?",job).get("requestId")).isBlank());
        }finally{db.execute("DELETE FROM sys_operation_log WHERE action='NOTIFICATION_RETRY' AND target_id=?",job);db.execute("DELETE FROM notification_outbox WHERE id IN (?,?)",job,other);}
    }
    @Test void actualImageUploadAndSignedDownloadArePrivate()throws Exception{
        java.io.ByteArrayOutputStream bytes=new java.io.ByteArrayOutputStream();javax.imageio.ImageIO.write(new java.awt.image.BufferedImage(2,2,java.awt.image.BufferedImage.TYPE_INT_RGB),"png",bytes);
        var resource=new org.springframework.core.io.ByteArrayResource(bytes.toByteArray()){public String getFilename(){return "test.png";}};
        var form=new org.springframework.util.LinkedMultiValueMap<String,Object>();form.add("file",resource);HttpHeaders headers=new HttpHeaders();headers.setBearerAuth(resident);headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        var upload=ok(rest.exchange("/api/files",HttpMethod.POST,new HttpEntity<>(form,headers),JsonNode.class));String file=upload.path("attachmentId").asText();assertFalse(file.isBlank());
        assertEquals(404,call(login("resident2"),HttpMethod.GET,"/api/files/"+file+"/access-url",null,null).getStatusCode().value());String url=ok(call(resident,HttpMethod.GET,"/api/files/"+file+"/access-url",null,null)).path("url").asText();var image=rest.getForEntity(url,byte[].class);assertEquals(200,image.getStatusCode().value());assertArrayEquals(bytes.toByteArray(),image.getBody());assertEquals(403,rest.getForEntity(url+"invalid",String.class).getStatusCode().value());
        var body=new HashMap<>(createBody());body.put("attachmentIds",List.of(file));var order=ok(call(resident,HttpMethod.POST,"/api/repair/orders",body,UUID.randomUUID().toString()));assertEquals("ATTACHED",db.required("SELECT status FROM file_asset WHERE id=?",file).get("status"));action(resident,order,"cancel",Map.of("reason","测试结束"));
    }
}
