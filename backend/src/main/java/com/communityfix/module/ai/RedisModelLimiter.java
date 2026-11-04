package com.communityfix.module.ai;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import java.util.List;
import java.util.UUID;

/** Atomic shared sliding window, using Redis time rather than application clocks. */
@Component
public class RedisModelLimiter {
    static final DefaultRedisScript<Long> SCRIPT=new DefaultRedisScript<>("""
        local t=redis.call('TIME')
        local now=tonumber(t[1])*1000+math.floor(tonumber(t[2])/1000)
        redis.call('ZREMRANGEBYSCORE',KEYS[1],'-inf',now-60000)
        if redis.call('ZCARD',KEYS[1])>=tonumber(ARGV[1]) then return 0 end
        redis.call('ZADD',KEYS[1],now,ARGV[2])
        redis.call('PEXPIRE',KEYS[1],60000)
        return 1
        """,Long.class);
    private final StringRedisTemplate redis;
    private final boolean enabled;
    private final int limit;
    private final String namespace;
    public RedisModelLimiter(StringRedisTemplate redis,
            @Value("${communityfix.redis.enabled:false}") boolean enabled,
            @Value("${communityfix.ai.provider-requests-per-minute:30}") int limit,
            @Value("${communityfix.redis.namespace:communityfix}") String namespace){
        if(limit<1||limit>100000||!namespace.matches("[a-zA-Z0-9_-]{1,64}"))throw new IllegalArgumentException("Invalid Redis limiter configuration");
        this.redis=redis;this.enabled=enabled;this.limit=limit;this.namespace=namespace;
    }
    public String acquire(ModelRoutes.Route route){
        if(!enabled)return null;
        try {
            Long result=redis.execute(SCRIPT,List.of(key(route)),Integer.toString(limit),UUID.randomUUID().toString());
            if(Long.valueOf(1).equals(result))return null;
            return Long.valueOf(0).equals(result)?"AI_PROVIDER_LOCAL_RATE_LIMIT":"AI_LIMITER_UNAVAILABLE";
        }catch(org.springframework.dao.DataAccessException e){return "AI_LIMITER_UNAVAILABLE";}
    }
    String key(ModelRoutes.Route route){return namespace+":ai:rpm:"+AiGuard.providerKey(route.endpoint(),route.model());}
}
