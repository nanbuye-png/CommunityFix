package com.communityfix.module.notification;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

@Component
public class WechatMessageSender {
    private static final String API = "https://api.weixin.qq.com";
    private final ObjectMapper json;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final String appId, appSecret, templateId, orderField, statusField;
    @Value("${communityfix.wechat.quote-template-id:}") private String quoteTemplate="";
    @Value("${communityfix.wechat.quote-order-field:}") private String quoteOrderField="";
    @Value("${communityfix.wechat.quote-amount-field:}") private String quoteAmountField="";
    private String accessToken;
    @Value("${communityfix.wechat.confirmation-template-id:0FK0JBc2x7cjS9i6N2SokPsB-FLjMZK9Ua7O9clGzx8}") private String confirmationTemplate="0FK0JBc2x7cjS9i6N2SokPsB-FLjMZK9Ua7O9clGzx8";
    @Value("${communityfix.wechat.payment-template-id:payqWcSKkakhM-DNMteuYfjVsQFKk4wDdSYODl15uII}") private String paymentTemplate="payqWcSKkakhM-DNMteuYfjVsQFKk4wDdSYODl15uII";
    public String confirmationTemplateId(){return confirmationTemplate;}
    public String paymentTemplateId(){return paymentTemplate;}
    public boolean confirmationEnabled(){return !appId.isBlank()&&!appSecret.isBlank()&&!confirmationTemplate.isBlank();}
    public boolean paymentEnabled(){return !appId.isBlank()&&!appSecret.isBlank()&&!paymentTemplate.isBlank();}
    @Value("${communityfix.wechat.acceptance-template-id:5Rdms3X2C7xEcXijCjWKlqxNd2RcDApD4GmHDDCGwKk}") private String acceptanceTemplate="5Rdms3X2C7xEcXijCjWKlqxNd2RcDApD4GmHDDCGwKk";
    public String acceptanceTemplateId(){return acceptanceTemplate;}
    @Value("${communityfix.wechat.completion-template-id:cmprBc8HFLj9-w7080XZiZdhzMic8yRVzbtUstEJwkg}") private String completionTemplate="cmprBc8HFLj9-w7080XZiZdhzMic8yRVzbtUstEJwkg";
    public String completionTemplateId(){return completionTemplate;}
    public boolean completionEnabled(){return !appId.isBlank()&&!appSecret.isBlank()&&!completionTemplate.isBlank();}
    public boolean acceptanceEnabled(){return !appId.isBlank()&&!appSecret.isBlank()&&!acceptanceTemplate.isBlank();}
    private Instant tokenExpiresAt = Instant.EPOCH;

    public WechatMessageSender(ObjectMapper json,
            @Value("${communityfix.wechat.app-id}") String appId,
            @Value("${communityfix.wechat.app-secret}") String appSecret,
            @Value("${communityfix.wechat.order-template-id}") String templateId,
            @Value("${communityfix.wechat.order-number-field}") String orderField,
            @Value("${communityfix.wechat.order-status-field}") String statusField) {
        this.json = json;
        this.appId = appId;
        this.appSecret = appSecret;
        this.templateId = templateId;
        this.orderField = orderField;
        this.statusField = statusField;
    }

    public boolean enabled(){return confirmationEnabled()||paymentEnabled()||completionEnabled()||acceptanceEnabled()||orderEnabled()||quoteEnabled();}
    public boolean orderEnabled() {
        return !appId.isBlank() && !appSecret.isBlank() && !templateId.isBlank()
                && !orderField.isBlank() && !statusField.isBlank() && !orderField.equals(statusField);
    }

    public String quoteTemplateId(){return quoteTemplate;}
    public boolean quoteEnabled(){return !appId.isBlank()&&!appSecret.isBlank()&&!quoteTemplate.isBlank()&&!quoteOrderField.isBlank()&&!quoteAmountField.isBlank()&&!quoteOrderField.equals(quoteAmountField);}
    public String messageBody(String openid,String payload) throws Exception {
        var accepted=json.readTree(payload);
        if("CONFIRMATION".equals(accepted.path("kind").asText())||"PAYMENT_DUE".equals(accepted.path("kind").asText())){
            boolean payment="PAYMENT_DUE".equals(accepted.path("kind").asText());
            String selected=payment?paymentTemplate:confirmationTemplate;
            if(!(payment?paymentEnabled():confirmationEnabled())||!selected.equals(accepted.path("templateId").asText()))throw new IllegalStateException("Billing template changed or not configured");
            Map<String,Object> data=new java.util.LinkedHashMap<>();
            data.put("character_string1",Map.of("value",accepted.path("orderNo").asText()));
            data.put("amount3",Map.of("value",java.math.BigDecimal.valueOf(accepted.path("amountFen").asLong(),2).toPlainString()));
            data.put(payment?"time22":"time6",Map.of("value",accepted.path("createdAt").asText()));
            if(payment){data.put("thing2",Map.of("value",clip(accepted.path("content").asText(),20)));data.put("date10",Map.of("value",accepted.path("completedAt").asText()));}
            return json.writeValueAsString(Map.of("touser",openid,"template_id",selected,"page",accepted.path("page").asText(),"data",data));
        }
        if("COMPLETION".equals(accepted.path("kind").asText())){
            if(!completionEnabled()||!completionTemplate.equals(accepted.path("templateId").asText()))throw new IllegalStateException("Completion template changed or not configured");
            var data=Map.of("character_string5",Map.of("value",accepted.path("orderNo").asText()),
                "amount11",Map.of("value",java.math.BigDecimal.valueOf(accepted.path("amountFen").asLong(),2).toPlainString()),
                "date9",Map.of("value",accepted.path("completedAt").asText()));
            return json.writeValueAsString(Map.of("touser",openid,"template_id",completionTemplate,"page",accepted.path("page").asText(),"data",data));
        }
        if("ACCEPTANCE".equals(accepted.path("kind").asText())){
            if(!acceptanceEnabled()||!acceptanceTemplate.equals(accepted.path("templateId").asText()))throw new IllegalStateException("Acceptance template changed or not configured");
            var data=Map.of("character_string7",Map.of("value",accepted.path("orderNo").asText()),
                "character_string30",Map.of("value",accepted.path("orderNo").asText()),
                "thing27",Map.of("value",clip(accepted.path("service").asText(),20)),
                "name5",Map.of("value",clip(accepted.path("worker").asText(),10)),
                "date4",Map.of("value",accepted.path("acceptedAt").asText()));
            return json.writeValueAsString(Map.of("touser",openid,"template_id",acceptanceTemplate,"page",accepted.path("page").asText(),"data",data));
        }
        var event=json.readTree(payload);boolean quote="QUOTE".equals(event.path("kind").asText());
        if(quote?!quoteEnabled():!orderEnabled())throw new IllegalStateException("Notification template not configured");
        String selected=quote?quoteTemplate:templateId;
        if(event.has("templateId")&&!selected.equals(event.path("templateId").asText()))throw new IllegalStateException("Queued template changed");
        var fields=quote?Map.of(quoteOrderField,Map.of("value",event.path("orderNo").asText()),quoteAmountField,Map.of("value",java.math.BigDecimal.valueOf(event.path("amountFen").asLong(),2).toPlainString())):Map.of(orderField,Map.of("value",event.path("orderNo").asText()),statusField,Map.of("value",event.path("status").asText()));
        return json.writeValueAsString(Map.of("touser",openid,"template_id",selected,"page",event.path("page").asText(),"data",fields));
    }
    public String templateId() { return templateId; }
    private static String clip(String value,int max){return value.codePointCount(0,value.length())<=max?value:value.substring(0,value.offsetByCodePoints(0,max));}

    public void send(String openid, String payload) throws Exception {
        if (!enabled()) throw new IllegalStateException("WeChat subscription delivery is not configured");
        String body = messageBody(openid,payload);
        HttpRequest request = HttpRequest.newBuilder(URI.create(API + "/cgi-bin/message/subscribe/send?access_token=" + token()))
                .header("Content-Type", "application/json;charset=UTF-8")
                .timeout(Duration.ofSeconds(8))
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        JsonNode result = json.readTree(response.body());
        int code = result.path("errcode").asInt(-1);
        if (response.statusCode() != 200 || code != 0) {
            if (code == 40001 || code == 42001) invalidateToken();
            throw new IOException("WeChat subscription API failed: HTTP " + response.statusCode() + ", errcode " + code);
        }
    }

    private synchronized String token() throws Exception {
        if (accessToken != null && Instant.now().isBefore(tokenExpiresAt)) return accessToken;
        URI uri = URI.create(API + "/cgi-bin/token?grant_type=client_credential&appid=" + encode(appId)
                + "&secret=" + encode(appSecret));
        HttpResponse<String> response = http.send(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(8)).GET().build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        JsonNode result = json.readTree(response.body());
        if (response.statusCode() != 200 || !result.hasNonNull("access_token")) {
            throw new IOException("WeChat token API failed: HTTP " + response.statusCode() + ", errcode " + result.path("errcode").asInt(-1));
        }
        accessToken = result.path("access_token").asText();
        tokenExpiresAt = Instant.now().plusSeconds(Math.max(60, result.path("expires_in").asLong(7200) - 120));
        return accessToken;
    }

    private synchronized void invalidateToken() {
        accessToken = null;
        tokenExpiresAt = Instant.EPOCH;
    }

    private String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }
}
