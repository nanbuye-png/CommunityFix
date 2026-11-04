package com.communityfix;

import com.communityfix.common.Db;
import com.communityfix.module.ai.AiGuard;
import com.fasterxml.jackson.databind.*;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={"communityfix.wechat.order-template-id=","communityfix.ai.api-key=local-stub-key","communityfix.ai.model=local-stub-model"})
@EnabledIfEnvironmentVariable(named="CF_INTEGRATION",matches="true")
class AiProviderIntegrationTest {
    private static final AtomicInteger requests=new AtomicInteger();
    private static final AtomicReference<String> mode=new AtomicReference<>("valid"),captured=new AtomicReference<>("");
    private static final HttpServer provider=startProvider();
    private static final String endpoint="http://127.0.0.1:"+provider.getAddress().getPort()+"/chat/completions";
    private static HttpServer startProvider(){
        try{
            var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
            server.createContext("/chat/completions",exchange->{
                requests.incrementAndGet();captured.set(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));
                String content=switch(mode.get()){
                    case "invalid" -> "not JSON";
                    case "unknown" -> "{\"categoryId\":\"99999999\",\"priority\":\"URGENT\",\"summary\":\"无效分类\"}";
                    case "unsafe" -> "{\"categoryId\":\"1\",\"priority\":\"HIGH\",\"summary\":\"虚构已检修；危险操作步骤；保证五分钟上门\",\"instructions\":\"不得展示的操作\"}";
                    case "labelsOnly" -> "{\"categoryId\":\"1\",\"priority\":\"NORMAL\"}";
                    default -> "{\"categoryId\":\"1\",\"priority\":\"NORMAL\",\"summary\":\"厨房水管漏水，联系13812345678\"}";
                };
                byte[] body=new ObjectMapper().writeValueAsBytes(Map.of("choices",List.of(Map.of("message",Map.of("content",content)))));
                exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(mode.get().equals("rateLimit")?429:mode.get().equals("error")?503:200,body.length);
                exchange.getResponseBody().write(body);exchange.close();
            });server.start();return server;
        }catch(Exception ex){throw new ExceptionInInitializerError(ex);}
    }
    @DynamicPropertySource static void configure(DynamicPropertyRegistry registry){registry.add("communityfix.ai.endpoint",()->endpoint);}
    @Autowired TestRestTemplate rest;
    @Autowired Db db;
    private String token;private long user;
    @BeforeEach void setup(){
        mode.set("valid");requests.set(0);db.execute("DELETE FROM ai_circuit_state WHERE provider_key=?",AiGuard.providerKey(endpoint,"local-stub-model"));
        var response=rest.postForEntity("/api/auth/register",Map.of("username","ai"+UUID.randomUUID().toString().replace("-","").substring(0,16),"password","ai-integration-pass-2026","nickname","AI测试居民"),JsonNode.class);
        assertEquals(200,response.getStatusCode().value());var data=response.getBody().path("data");token=data.path("token").asText();user=data.path("user").path("id").asLong();
    }
    private JsonNode analyze(String text){HttpHeaders headers=new HttpHeaders();headers.setBearerAuth(token);var response=rest.exchange("/api/ai/repair-analysis",HttpMethod.POST,new HttpEntity<>(Map.of("description",text),headers),JsonNode.class);assertEquals(200,response.getStatusCode().value());return response.getBody().path("data");}
    @AfterEach void cleanup(){db.execute("DELETE FROM ai_analysis_record WHERE user_id=?",user);db.execute("DELETE FROM ai_request_permit WHERE user_id=?",user);db.execute("DELETE FROM sys_user_role WHERE user_id=?",user);db.execute("DELETE FROM sys_user WHERE id=?",user);db.execute("DELETE FROM ai_circuit_state WHERE provider_key=?",AiGuard.providerKey(endpoint,"local-stub-model"));}
    @AfterAll static void stopProvider(){provider.stop(0);}

    @Test void validSuggestionUsesRedactedInputAndStoresRedactedOutput(){
        var result=analyze("AI测试居民在3栋1单元602室水管漏水，电话13812345678");
        assertEquals("AI",result.path("source").asText());assertEquals("1",result.path("categoryId").asText());
        assertFalse(captured.get().contains("13812345678"));assertFalse(captured.get().contains("AI测试居民"));assertFalse(captured.get().contains("602室"));
        var row=db.required("SELECT status,input_text,summary,error_code FROM ai_analysis_record WHERE id=?",result.path("analysisId").asText());
        assertEquals("SUCCESS",row.get("status"));assertNull(row.get("errorCode"));assertFalse(row.get("summary").toString().contains("13812345678"));
    }
    @Test void invalidResponsesFallBackWithoutChangingInput(){
        for(String invalid:List.of("invalid","unknown")){mode.set(invalid);var result=analyze("原始报修描述");assertEquals("MANUAL",result.path("source").asText());assertEquals("原始报修描述",result.path("summary").asText());assertTrue(result.path("categoryId").isNull());}
    }
    @Test void providerRateLimitIsRecordedSeparatelyFromInvalidAnswers(){
        mode.set("rateLimit");var result=analyze("水管漏水");
        assertEquals("MANUAL",result.path("source").asText());assertEquals(1,requests.get());
        assertEquals("AI_PROVIDER_RATE_LIMIT",db.required("SELECT error_code FROM ai_analysis_record WHERE id=?",result.path("analysisId").asText()).get("errorCode"));
    }
    @Test void unsafeFreeTextIsNeverReturnedOrRetainedAndUrgencyHasFloor(){
        mode.set("unsafe");
        for(String text:List.of("微波炉内部出现明火，火焰已经蔓延到炉门边缘。","洗衣机内的水喷到带电部件并出现火花。")){
            var result=analyze(text);
            assertEquals("AI",result.path("source").asText());
            assertEquals("URGENT",result.path("priority").asText());
            assertEquals(text,result.path("summary").asText());
            assertEquals("ORIGINAL",result.path("summarySource").asText());
            assertFalse(result.toString().contains("虚构已检修"));
            var row=db.required("SELECT summary,priority_result FROM ai_analysis_record WHERE id=?",result.path("analysisId").asText());
            assertEquals(text,row.get("summary"));assertEquals("URGENT",row.get("priorityResult"));
        }
    }
    @Test void urgentNoticeSurvivesProviderFailureWithoutPretendingAiSucceeded(){
        mode.set("error");var result=analyze("插座正在冒火花。");
        assertEquals("MANUAL",result.path("source").asText());assertTrue(result.path("priority").isNull());
        assertEquals("URGENT",result.path("safetyPriority").asText());assertFalse(result.path("safetyNotice").asText().isBlank());
        assertEquals("插座正在冒火花。",result.path("summary").asText());
    }
    @Test void labelsOnlyResponseIsValidAndKeepsOriginalText(){
        mode.set("labelsOnly");var result=analyze("水龙头滴水，已关闭阀门，没有火花或烟雾。");
        assertEquals("AI",result.path("source").asText());assertEquals("NORMAL",result.path("priority").asText());
        assertTrue(result.path("safetyPriority").isNull());
        assertEquals("水龙头滴水，已关闭阀门，没有火花或烟雾。",result.path("summary").asText());
    }
    @Test void providerFailuresOpenCircuitAndSuccessfulProbeRecovers(){
        mode.set("error");for(int i=0;i<3;i++)assertEquals("MANUAL",analyze("水管漏水").path("source").asText());
        var blocked=analyze("插座正在冒火花。");assertEquals(3,requests.get());assertEquals("MANUAL",blocked.path("source").asText());
        assertEquals("URGENT",blocked.path("safetyPriority").asText());assertTrue(blocked.path("priority").isNull());
        assertEquals("AI_CIRCUIT_OPEN",db.required("SELECT error_code FROM ai_analysis_record WHERE id=?",blocked.path("analysisId").asText()).get("errorCode"));
        db.execute("UPDATE ai_circuit_state SET open_until=DATE_SUB(UTC_TIMESTAMP(3),INTERVAL 1 SECOND) WHERE provider_key=?",AiGuard.providerKey(endpoint,"local-stub-model"));mode.set("valid");
        assertEquals("AI",analyze("恢复探测").path("source").asText());assertEquals(4,requests.get());
        assertNull(db.required("SELECT open_until FROM ai_circuit_state WHERE provider_key=?",AiGuard.providerKey(endpoint,"local-stub-model")).get("openUntil"));
    }
}
