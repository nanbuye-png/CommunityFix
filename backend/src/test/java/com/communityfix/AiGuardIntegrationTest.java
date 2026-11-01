package com.communityfix;

import com.communityfix.common.*;
import com.communityfix.module.ai.AiGuard;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties={"communityfix.ai.endpoint=","communityfix.wechat.order-template-id="})
@EnabledIfEnvironmentVariable(named="CF_INTEGRATION",matches="true")
class AiGuardIntegrationTest {
    @Autowired Db db;
    @Autowired AiGuard guard;
    @Autowired PlatformTransactionManager transactions;
    private <T>T tx(Supplier<T> action){return new TransactionTemplate(transactions).execute(s->action.get());}

    @Test void twoInstancesShareOneSlidingMinuteLimitUnderConcurrency() throws Exception {
        long user=db.insert("sys_user",Db.fields("nickname","AI限流测试"));
        AiGuard other=new AiGuard(db,3,60);
        try(var executor=Executors.newFixedThreadPool(10)){
            CountDownLatch gate=new CountDownLatch(1);List<Future<Integer>> calls=new ArrayList<>();
            for(int i=0;i<20;i++){boolean second=i%2==0;calls.add(executor.submit(()->{gate.await();try{if(second)tx(()->{other.reserveRequest(user);return null;});else guard.reserveRequest(user);return 200;}catch(ApiException ex){return ex.status;}}));}
            gate.countDown();List<Integer> results=new ArrayList<>();for(var call:calls)results.add(call.get(20,TimeUnit.SECONDS));
            assertEquals(5,results.stream().filter(code->code==200).count());assertEquals(15,results.stream().filter(code->code==429).count());
            assertEquals(5,db.count("SELECT COUNT(*) FROM ai_request_permit WHERE user_id=?",user));
            db.execute("UPDATE ai_request_permit SET requested_at=DATE_SUB(UTC_TIMESTAMP(3),INTERVAL 61 SECOND) WHERE user_id=?",user);
            tx(()->{other.reserveRequest(user);return null;});
            assertEquals(1,db.count("SELECT COUNT(*) FROM ai_request_permit WHERE user_id=? AND requested_at>DATE_SUB(UTC_TIMESTAMP(3),INTERVAL 60 SECOND)",user));
        }finally{db.execute("DELETE FROM ai_request_permit WHERE user_id=?",user);db.execute("DELETE FROM sys_user WHERE id=?",user);}
    }

    @Test void circuitIsSharedAndOnlyOneRecoveryProbeCanRun() throws Exception {
        String endpoint="https://test.invalid/"+UUID.randomUUID(),model="test-model",key=AiGuard.providerKey(endpoint,model);
        AiGuard other=new AiGuard(db,3,60);
        try{
            var stale=guard.tryProvider(endpoint,model);
            for(int i=0;i<3;i++){var permit=tx(()->other.tryProvider(endpoint,model));assertNotNull(permit);guard.failed(permit);}
            assertNull(tx(()->other.tryProvider(endpoint,model)));
            guard.succeeded(stale);assertNull(guard.tryProvider(endpoint,model));
            db.execute("UPDATE ai_circuit_state SET open_until=DATE_SUB(UTC_TIMESTAMP(3),INTERVAL 1 SECOND) WHERE provider_key=?",key);
            List<AiGuard.Permit> probes=new ArrayList<>();
            try(var executor=Executors.newFixedThreadPool(8)){
                CountDownLatch gate=new CountDownLatch(1);List<Future<AiGuard.Permit>> calls=new ArrayList<>();
                for(int i=0;i<8;i++)calls.add(executor.submit(()->{gate.await();return tx(()->other.tryProvider(endpoint,model));}));
                gate.countDown();for(var call:calls){var permit=call.get(20,TimeUnit.SECONDS);if(permit!=null)probes.add(permit);}
            }
            assertEquals(1,probes.size());assertTrue(probes.getFirst().probe());
            var oldProbe=probes.getFirst();
            db.execute("UPDATE ai_circuit_state SET probe_until=DATE_SUB(UTC_TIMESTAMP(3),INTERVAL 1 SECOND) WHERE provider_key=?",key);
            var newProbe=guard.tryProvider(endpoint,model);assertNotNull(newProbe);assertTrue(newProbe.generation()>oldProbe.generation());
            guard.succeeded(oldProbe);assertNull(guard.tryProvider(endpoint,model));
            tx(()->{other.succeeded(newProbe);return null;});
            assertFalse(guard.tryProvider(endpoint,model).probe());
            guard.failed(oldProbe);assertNotNull(guard.tryProvider(endpoint,model));
        }finally{db.execute("DELETE FROM ai_circuit_state WHERE provider_key=?",key);}
    }
}
