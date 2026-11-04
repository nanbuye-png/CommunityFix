package com.communityfix.module.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import java.io.IOException;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;

/** The only provider-protocol adapter; business validation belongs to its caller. */
@Component
public class ChatCompletionsClient {
    public record Usage(Long promptTokens,Long completionTokens,Long totalTokens) {}
    public record Reply(JsonNode content,Usage usage) {}
    private final ObjectMapper json;
    private final HttpClient client;
    private final Duration timeout;
    @org.springframework.beans.factory.annotation.Autowired
    public ChatCompletionsClient(ObjectMapper json){this(json,HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build(),Duration.ofSeconds(10));}
    // Package-private injection supports short, deterministic timeout tests.
    ChatCompletionsClient(ObjectMapper json,HttpClient client,Duration timeout){this.json=json;this.client=client;this.timeout=timeout;}
    public Reply complete(ModelRoutes.Route route,List<Map<String,String>> messages) {
        HttpResponse<String> response;
        try {
            var body=Map.of("model",route.model(),"temperature",0,"max_tokens",1024,"response_format",Map.of("type","json_object"),"messages",messages);
            var request=HttpRequest.newBuilder(URI.create(route.endpoint())).header("Authorization","Bearer "+route.key()).header("Content-Type","application/json")
                .timeout(timeout).POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build();
            response=client.send(request,HttpResponse.BodyHandlers.ofString());
        } catch(HttpTimeoutException e){throw new ModelFailure("AI_TIMEOUT");}
          catch(InterruptedException e){Thread.currentThread().interrupt();throw new ModelFailure("AI_INTERRUPTED");}
          catch(IOException e){throw new ModelFailure("AI_NETWORK_ERROR");}
          catch(IllegalArgumentException e){throw new ModelFailure("AI_INVALID_CONFIG");}
        int status=response.statusCode();
        if(status!=200)throw new ModelFailure(status==429?"AI_PROVIDER_RATE_LIMIT":status==401||status==403?"AI_PROVIDER_AUTH_ERROR":"AI_PROVIDER_HTTP_ERROR",status);
        try {
            var envelope=json.readTree(response.body());
            if(envelope==null)throw new ModelFailure("AI_INVALID_RESPONSE");
            var choice=envelope.path("choices").path(0);
            if("length".equals(choice.path("finish_reason").asText()))throw new ModelFailure("AI_TRUNCATED_RESPONSE");
            var content=choice.path("message").path("content");
            if(content.isMissingNode()||content.isNull()||content.isTextual()&&content.asText().isBlank())throw new ModelFailure("AI_EMPTY_RESPONSE");
            if(!content.isTextual())throw new ModelFailure("AI_INVALID_RESPONSE");
            var result=json.readTree(content.asText());
            if(result==null||!result.isObject())throw new ModelFailure("AI_INVALID_RESPONSE");
            var usage=envelope.path("usage");
            return new Reply(result,new Usage(count(usage,"prompt_tokens"),count(usage,"completion_tokens"),count(usage,"total_tokens")));
        } catch(IOException e){throw new ModelFailure("AI_INVALID_RESPONSE");}
    }
    private static Long count(JsonNode usage,String field){var n=usage.path(field);return n.isIntegralNumber()&&n.canConvertToLong()&&n.longValue()>=0?n.longValue():null;}
}
