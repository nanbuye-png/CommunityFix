package com.communityfix.module.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import static org.junit.jupiter.api.Assertions.*;

class ChatCompletionsClientTest {
    private final ObjectMapper json=new ObjectMapper();
    private HttpServer server;
    private ExecutorService workers;
    private ModelRoutes.Route route;
    private volatile String response,request,authorization;
    private volatile int status=200;
    private volatile long delay=0;
    private final List<Map<String,String>> messages=List.of(Map.of("role","user","content","测试报修"));
    @BeforeEach void start() throws Exception {
        response=envelope("{\"priority\":\"HIGH\"}","stop",Map.of("prompt_tokens",12,"completion_tokens",8,"total_tokens",20));
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        workers=Executors.newCachedThreadPool();server.setExecutor(workers);
        server.createContext("/chat/completions",exchange->{
            try {
                request=new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8);
                authorization=exchange.getRequestHeaders().getFirst("Authorization");
                if(delay>0)Thread.sleep(delay);
                byte[] bytes=response.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type","application/json; charset=utf-8");
                exchange.sendResponseHeaders(status,bytes.length);exchange.getResponseBody().write(bytes);
            } catch(InterruptedException e){Thread.currentThread().interrupt();}
            finally {exchange.close();}
        });server.start();
        route=new ModelRoutes.Route("http://127.0.0.1:"+server.getAddress().getPort()+"/chat/completions","stub-secret","stub-model");
    }
    @AfterEach void stop(){server.stop(0);workers.shutdownNow();}
    private String envelope(Object content,String reason,Map<String,Object> usage) throws Exception {
        return json.writeValueAsString(Map.of("choices",List.of(Map.of("finish_reason",reason,"message",Map.of("content",content))),"usage",usage));
    }
    private ChatCompletionsClient client(){return new ChatCompletionsClient(json);}
    @Test void sendsCompatibleRequestAndParsesStructuredAnswerAndUsage() throws Exception {
        var reply=client().complete(route,messages);
        assertEquals("HIGH",reply.content().path("priority").asText());assertEquals(20L,reply.usage().totalTokens());
        assertEquals("Bearer stub-secret",authorization);
        var body=json.readTree(request);assertEquals("stub-model",body.path("model").asText());
        assertEquals("json_object",body.path("response_format").path("type").asText());
        assertEquals("测试报修",body.path("messages").path(0).path("content").asText());
    }
    @Test void absentOrInvalidUsageIsUnknownRatherThanZero() throws Exception {
        response=envelope("{}","stop",Map.of("prompt_tokens",-1,"completion_tokens","10"));
        var usage=client().complete(route,messages).usage();
        assertNull(usage.promptTokens());assertNull(usage.completionTokens());assertNull(usage.totalTokens());
    }
    @Test void classifiesHttpFailuresWithoutExposingUpstreamBody(){
        response="upstream account secret private prompt";
        for(var sample:Map.of(429,"AI_PROVIDER_RATE_LIMIT",401,"AI_PROVIDER_AUTH_ERROR",403,"AI_PROVIDER_AUTH_ERROR",503,"AI_PROVIDER_HTTP_ERROR").entrySet()){
            status=sample.getKey();var error=assertThrows(ModelFailure.class,()->client().complete(route,messages));
            assertEquals(sample.getValue(),error.code());assertEquals(status,error.httpStatus());
            assertFalse(error.toString().contains("secret"));assertNull(error.getCause());
        }
    }
    @Test void rejectsEmptyMalformedAndTruncatedAnswers() throws Exception {
        for(var sample:Map.of("","AI_EMPTY_RESPONSE","not json","AI_INVALID_RESPONSE","[]","AI_INVALID_RESPONSE","null","AI_INVALID_RESPONSE").entrySet()){
            response=envelope(sample.getKey(),"stop",Map.of());
            assertEquals(sample.getValue(),assertThrows(ModelFailure.class,()->client().complete(route,messages)).code());
        }
        response=envelope("{}","length",Map.of());
        assertEquals("AI_TRUNCATED_RESPONSE",assertThrows(ModelFailure.class,()->client().complete(route,messages)).code());
        response="<html>bad gateway</html>";
        assertEquals("AI_INVALID_RESPONSE",assertThrows(ModelFailure.class,()->client().complete(route,messages)).code());
        response="{\"choices\":[]}";
        assertEquals("AI_EMPTY_RESPONSE",assertThrows(ModelFailure.class,()->client().complete(route,messages)).code());
        response=envelope(Map.of("priority","HIGH"),"stop",Map.of());
        assertEquals("AI_INVALID_RESPONSE",assertThrows(ModelFailure.class,()->client().complete(route,messages)).code());
    }
    @Test void timeoutHasDistinctFailureCode(){
        delay=2000;
        var client=new ChatCompletionsClient(json,HttpClient.newHttpClient(),Duration.ofMillis(150));
        assertEquals("AI_TIMEOUT",assertThrows(ModelFailure.class,()->client.complete(route,messages)).code());
    }
}
