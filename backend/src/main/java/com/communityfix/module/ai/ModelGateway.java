package com.communityfix.module.ai;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import java.util.*;
import java.util.function.Function;

/** In-process model gateway. No database transaction spans the network request. */
@Service
public class ModelGateway {
    public record Result<T>(T value,String error,String model,long durationMs,ChatCompletionsClient.Usage usage,Integer httpStatus) {
        public boolean successful(){return error==null;}
    }
    private static final Logger log=LoggerFactory.getLogger(ModelGateway.class);
    @org.springframework.beans.factory.annotation.Autowired(required=false) private AiDailyUsage dailyUsage;
    private final ModelRoutes routes;
    private final ChatCompletionsClient client;
    private final AiGuard guard;
    private final RedisModelLimiter limiter;
    public ModelGateway(ModelRoutes routes,ChatCompletionsClient client,AiGuard guard,RedisModelLimiter limiter){this.routes=routes;this.client=client;this.guard=guard;this.limiter=limiter;}
    public boolean configured(ModelRoutes.Scene scene){return routes.forScene(scene).configured();}
    public String model(ModelRoutes.Scene scene){return routes.forScene(scene).model();}
    public <T> Result<T> complete(ModelRoutes.Scene scene,List<Map<String,String>> messages,Function<JsonNode,T> validate) {
        long start=System.nanoTime();var route=routes.forScene(scene);
        if(!route.configured())return finish(scene,start,route,null,"AI_NOT_CONFIGURED",null,null);
        String admission=limiter.acquire(route);
        if(admission!=null)return finish(scene,start,route,null,admission,null,null);
        var permit=guard.tryProvider(route.endpoint(),route.model());
        if(permit==null)return finish(scene,start,route,null,"AI_CIRCUIT_OPEN",null,null);
        ChatCompletionsClient.Usage usage=null;
        T value;
        try {
            var reply=client.complete(route,messages);usage=reply.usage();
            try {value=Objects.requireNonNull(validate.apply(reply.content()));}
            catch(IllegalArgumentException|NullPointerException e){throw new ModelFailure("AI_INVALID_RESPONSE");}
        } catch(ModelFailure e) {
            guard.failed(permit);
            return finish(scene,start,route,null,e.code(),usage,e.httpStatus());
        } finally {
            if(dailyUsage!=null)try{dailyUsage.record(scene,usage);}catch(RuntimeException ex){log.warn("AI_USAGE_RECORD_FAILED scene={}",scene);}
        }
        guard.succeeded(permit);
        return finish(scene,start,route,value,null,usage,200);
    }
    private <T> Result<T> finish(ModelRoutes.Scene scene,long start,ModelRoutes.Route route,T value,String error,ChatCompletionsClient.Usage usage,Integer status){
        long elapsed=(System.nanoTime()-start)/1_000_000;
        // No prompt, answer, URL, model name, credentials, headers or raw upstream error text.
        log.info("model_call scene={} outcome={} duration_ms={} http_status={} prompt_tokens={} completion_tokens={} total_tokens={}",scene,error==null?"SUCCESS":error,elapsed,status,usage==null?null:usage.promptTokens(),usage==null?null:usage.completionTokens(),usage==null?null:usage.totalTokens());
        return new Result<>(value,error,route.model(),elapsed,usage,status);
    }
}
