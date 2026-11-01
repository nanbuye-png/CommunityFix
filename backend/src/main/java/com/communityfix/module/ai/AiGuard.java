package com.communityfix.module.ai;

import com.communityfix.common.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;

@Service
public class AiGuard {
    private final Db db;
    private final int failureThreshold,cooldownSeconds;
    public AiGuard(Db db,@Value("${communityfix.ai.failure-threshold:3}") int failureThreshold,
            @Value("${communityfix.ai.cooldown-seconds:60}") int cooldownSeconds) {
        if(failureThreshold<1||failureThreshold>100||cooldownSeconds<1||cooldownSeconds>3600)throw new IllegalArgumentException("Invalid AI circuit configuration");
        this.db=db;this.failureThreshold=failureThreshold;this.cooldownSeconds=cooldownSeconds;
    }

    @Transactional
    public void reserveRequest(long userId) {
        // Lock only during admission, never while calling the external provider.
        db.required("SELECT id FROM sys_user WHERE id=? FOR UPDATE",userId);
        if(db.count("SELECT COUNT(*) FROM ai_request_permit WHERE user_id=? AND requested_at>DATE_SUB(UTC_TIMESTAMP(3),INTERVAL 60 SECOND)",userId)>=5)
            throw new ApiException(429,"AI_RATE_LIMIT","分析次数过多，请稍后重试或人工填写");
        db.execute("INSERT INTO ai_request_permit(user_id) VALUES (?)",userId);
    }

    public record Permit(String providerKey,long generation,boolean probe) {}

    @Transactional
    public Permit tryProvider(String endpoint,String model) {
        String key=providerKey(endpoint,model);
        // A no-op upsert takes an exclusive lock; INSERT IGNORE followed by FOR UPDATE can deadlock on lock upgrades.
        db.execute("INSERT INTO ai_circuit_state(provider_key) VALUES (?) ON DUPLICATE KEY UPDATE provider_key=ai_circuit_state.provider_key",key);
        var state=db.required("SELECT *,UTC_TIMESTAMP(3) AS db_now FROM ai_circuit_state WHERE provider_key=? FOR UPDATE",key);
        Instant now=(Instant)state.get("dbNow"),openUntil=(Instant)state.get("openUntil"),probeUntil=(Instant)state.get("probeUntil");
        long generation=Db.id(state,"generation");
        if(openUntil==null)return new Permit(key,generation,false);
        if(openUntil.isAfter(now)||probeUntil!=null&&probeUntil.isAfter(now))return null;
        // Exactly one shared recovery probe. Its lease exceeds the provider's 10-second timeout.
        db.execute("UPDATE ai_circuit_state SET generation=generation+1,probe_until=DATE_ADD(UTC_TIMESTAMP(3),INTERVAL 30 SECOND),updated_at=UTC_TIMESTAMP(3) WHERE provider_key=?",key);
        return new Permit(key,generation+1,true);
    }

    @Transactional
    public void succeeded(Permit permit) {
        db.execute("UPDATE ai_circuit_state SET failure_count=0,open_until=NULL,probe_until=NULL,updated_at=UTC_TIMESTAMP(3) WHERE provider_key=? AND generation=?",permit.providerKey(),permit.generation());
    }

    @Transactional
    public void failed(Permit permit) {
        var state=db.required("SELECT failure_count,generation FROM ai_circuit_state WHERE provider_key=? FOR UPDATE",permit.providerKey());
        if(Db.id(state,"generation")!=permit.generation())return;
        int failures=Db.integer(state,"failureCount")+1;
        if(permit.probe()||failures>=failureThreshold)
            db.execute("UPDATE ai_circuit_state SET failure_count=?,generation=generation+1,open_until=DATE_ADD(UTC_TIMESTAMP(3),INTERVAL ? SECOND),probe_until=NULL,updated_at=UTC_TIMESTAMP(3) WHERE provider_key=?",failures,cooldownSeconds,permit.providerKey());
        else db.execute("UPDATE ai_circuit_state SET failure_count=?,updated_at=UTC_TIMESTAMP(3) WHERE provider_key=?",failures,permit.providerKey());
    }

    public static String providerKey(String endpoint,String model) {
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest((endpoint+"\n"+model).getBytes(StandardCharsets.UTF_8)));}
        catch(java.security.NoSuchAlgorithmException ex){throw new IllegalStateException(ex);}
    }
}
