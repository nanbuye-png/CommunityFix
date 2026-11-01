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
    private String accessToken;
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

    public boolean enabled() {
        return !appId.isBlank() && !appSecret.isBlank() && !templateId.isBlank()
                && !orderField.isBlank() && !statusField.isBlank() && !orderField.equals(statusField);
    }

    public String templateId() { return templateId; }

    public void send(String openid, String payload) throws Exception {
        if (!enabled()) throw new IllegalStateException("WeChat subscription delivery is not configured");
        JsonNode event = json.readTree(payload);
        String body = json.writeValueAsString(Map.of(
                "touser", openid,
                "template_id", templateId,
                "page", event.path("page").asText(),
                "data", Map.of(orderField, Map.of("value", event.path("orderNo").asText()),
                        statusField, Map.of("value", event.path("status").asText()))));
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
