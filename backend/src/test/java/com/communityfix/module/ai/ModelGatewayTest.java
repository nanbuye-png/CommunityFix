package com.communityfix.module.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ModelGatewayTest {
    private final AiGuard guard=mock(AiGuard.class);
    private final RedisModelLimiter limiter=mock(RedisModelLimiter.class);
    private final ChatCompletionsClient client=mock(ChatCompletionsClient.class);
    private final List<Map<String,String>> messages=List.of(Map.of("role","user","content","test"));
    private final AiGuard.Permit permit=new AiGuard.Permit("provider",1,false);
    private final ModelRoutes routes=new ModelRoutes(new MockEnvironment()
        .withProperty("communityfix.ai.endpoint","https://legacy.example/chat")
        .withProperty("communityfix.ai.api-key","secret").withProperty("communityfix.ai.model","model")
        .withProperty("communityfix.ai.support.endpoint","https://support.example/chat")
        .withProperty("communityfix.ai.support.api-key","support-secret").withProperty("communityfix.ai.support.model","support-model"));
    private final ModelGateway gateway=new ModelGateway(routes,client,guard,limiter);
    @Test void disabledAndOpenCircuitNeverContactProvider(){
        var disabled=new ModelGateway(new ModelRoutes(new MockEnvironment()),client,guard,limiter);
        assertEquals("AI_NOT_CONFIGURED",disabled.complete(ModelRoutes.Scene.REPAIR,messages,n->n).error());
        verifyNoInteractions(guard,client);
        assertEquals("AI_CIRCUIT_OPEN",gateway.complete(ModelRoutes.Scene.REPAIR,messages,n->n).error());
        verifyNoInteractions(client);verify(guard,never()).failed(any());
    }
    @Test void routingAndSuccessAreSharedAcrossBothScenes() throws Exception {
        var content=new ObjectMapper().readTree("{\"answer\":\"ok\"}");
        for(var scene:ModelRoutes.Scene.values()){
            var route=routes.forScene(scene);when(guard.tryProvider(route.endpoint(),route.model())).thenReturn(permit);
            when(client.complete(route,messages)).thenReturn(new ChatCompletionsClient.Reply(content,new ChatCompletionsClient.Usage(2L,3L,5L)));
            var result=gateway.complete(scene,messages,n->n.path("answer").asText());
            assertTrue(result.successful());assertEquals("ok",result.value());assertEquals(route.model(),result.model());
            assertEquals(5L,result.usage().totalTokens());verify(client).complete(route,messages);
        }
        verify(guard,times(2)).succeeded(permit);verify(guard,never()).failed(any());
    }
    @Test void businessValidationFailureCountsAsProviderFailure() throws Exception {
        var route=routes.forScene(ModelRoutes.Scene.REPAIR);
        when(guard.tryProvider(route.endpoint(),route.model())).thenReturn(permit);
        when(client.complete(route,messages)).thenReturn(new ChatCompletionsClient.Reply(new ObjectMapper().readTree("{}"),new ChatCompletionsClient.Usage(1L,2L,3L)));
        var result=gateway.complete(ModelRoutes.Scene.REPAIR,messages,n->{throw new IllegalArgumentException("unknown category");});
        assertEquals("AI_INVALID_RESPONSE",result.error());assertNull(result.value());assertEquals(3L,result.usage().totalTokens());
        verify(guard).failed(permit);verify(guard,never()).succeeded(any());
    }
    @Test void rateLimitIsPreservedWithoutRetry(){
        var route=routes.forScene(ModelRoutes.Scene.SUPPORT);
        when(guard.tryProvider(route.endpoint(),route.model())).thenReturn(permit);
        when(client.complete(route,messages)).thenThrow(new ModelFailure("AI_PROVIDER_RATE_LIMIT",429));
        var result=gateway.complete(ModelRoutes.Scene.SUPPORT,messages,n->n);
        assertEquals("AI_PROVIDER_RATE_LIMIT",result.error());assertEquals(429,result.httpStatus());
        verify(client).complete(route,messages);verify(guard).failed(permit);verify(guard,never()).succeeded(any());
    }
    @Test void redisDenialOrOutageNeverCallsProviderOrChangesCircuit(){
        var route=routes.forScene(ModelRoutes.Scene.REPAIR);
        for(String error:List.of("AI_PROVIDER_LOCAL_RATE_LIMIT","AI_LIMITER_UNAVAILABLE")){
            when(limiter.acquire(route)).thenReturn(error);
            assertEquals(error,gateway.complete(ModelRoutes.Scene.REPAIR,messages,n->n).error());
        }
        verifyNoInteractions(client,guard);
    }
}
