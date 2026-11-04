package com.communityfix.module.ai;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.RedisConnectionFailureException;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RedisModelLimiterTest {
    private final StringRedisTemplate redis=mock(StringRedisTemplate.class);
    private final ModelRoutes.Route route=new ModelRoutes.Route("https://example.com/chat","secret","model");
    @Test void disabledDoesNotConnect(){
        assertNull(new RedisModelLimiter(redis,false,30,"test").acquire(route));verifyNoInteractions(redis);
    }
    @Test void connectionFailureAndUnknownReplyFailClosed(){
        var limiter=new RedisModelLimiter(redis,true,30,"test");
        assertEquals("AI_LIMITER_UNAVAILABLE",limiter.acquire(route));
        when(redis.execute(eq(RedisModelLimiter.SCRIPT),anyList(),anyString(),anyString())).thenThrow(new RedisConnectionFailureException("private connection details"));
        assertEquals("AI_LIMITER_UNAVAILABLE",limiter.acquire(route));
    }
    @Test void validatesConfigurationAndHidesEndpointAndKey(){
        assertThrows(IllegalArgumentException.class,()->new RedisModelLimiter(redis,true,0,"test"));
        assertThrows(IllegalArgumentException.class,()->new RedisModelLimiter(redis,true,1,"bad:prefix"));
        var limiter=new RedisModelLimiter(redis,true,1,"test");
        assertFalse(limiter.key(route).contains("secret"));assertFalse(limiter.key(route).contains("example.com"));
    }
}
