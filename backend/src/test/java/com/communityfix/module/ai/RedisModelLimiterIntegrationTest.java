package com.communityfix.module.ai;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="CF_REDIS_INTEGRATION",matches="true")
class RedisModelLimiterIntegrationTest {
    private LettuceConnectionFactory first,second;
    private StringRedisTemplate a,b;
    private String prefix;
    private final ModelRoutes.Route route=new ModelRoutes.Route("https://stub.example/chat","synthetic","model");
    private LettuceConnectionFactory connect(){
        var config=new RedisStandaloneConfiguration("127.0.0.1",Integer.parseInt(System.getenv("CF_REDIS_PORT")));
        config.setPassword(System.getenv("CF_REDIS_PASSWORD"));
        var factory=new LettuceConnectionFactory(config);factory.afterPropertiesSet();return factory;
    }
    @BeforeEach void setup(){first=connect();second=connect();a=new StringRedisTemplate(first);b=new StringRedisTemplate(second);prefix="test_"+UUID.randomUUID().toString().replace("-","");}
    @AfterEach void cleanup(){
        a.delete(new RedisModelLimiter(a,true,5,prefix).key(route));
        first.destroy();second.destroy();
    }
    @Test void twoIndependentClientsCannotExceedSharedWindow() throws Exception {
        var one=new RedisModelLimiter(a,true,5,prefix);var two=new RedisModelLimiter(b,true,5,prefix);
        try(var pool=Executors.newFixedThreadPool(12)){
            List<Callable<String>> work=new ArrayList<>();
            for(int i=0;i<40;i++){var limiter=i%2==0?one:two;work.add(()->limiter.acquire(route));}
            int accepted=0;
            for(var result:pool.invokeAll(work)){String code=result.get();if(code==null)accepted++;else assertEquals("AI_PROVIDER_LOCAL_RATE_LIMIT",code);}
            assertEquals(5,accepted);
        }
        assertEquals(5L,a.opsForZSet().zCard(one.key(route)));
        Long ttl=a.getExpire(one.key(route),TimeUnit.MILLISECONDS);assertTrue(ttl>0&&ttl<=60000);
    }
    @Test void oldEntriesExpireAndNamespacesAreIsolated(){
        var limiter=new RedisModelLimiter(a,true,1,prefix);String key=limiter.key(route);
        a.opsForZSet().add(key,"expired",1);
        assertNull(limiter.acquire(route));assertEquals("AI_PROVIDER_LOCAL_RATE_LIMIT",limiter.acquire(route));
        var isolated=new RedisModelLimiter(b,true,1,prefix+"_other");
        try {assertNull(isolated.acquire(route));} finally {b.delete(isolated.key(route));}
        a.expire(key,Duration.ZERO);assertNull(limiter.acquire(route));
    }
}
