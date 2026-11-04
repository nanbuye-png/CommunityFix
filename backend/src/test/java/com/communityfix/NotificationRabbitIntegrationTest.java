package com.communityfix;

import com.communityfix.common.Db;
import com.communityfix.module.notification.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import java.time.Duration;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.awaitility.Awaitility.await;

@SpringBootTest(properties={"communityfix.rabbit.enabled=true","communityfix.rabbit.relay-initial-delay-ms=3600000","communityfix.support.jobs-enabled=false"})
@EnabledIfEnvironmentVariable(named="CF_RABBIT_INTEGRATION",matches="true")
@org.springframework.test.annotation.DirtiesContext(classMode=org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
class NotificationRabbitIntegrationTest {
    @DynamicPropertySource static void configure(DynamicPropertyRegistry r){
        r.add("spring.rabbitmq.host",()->"127.0.0.1");r.add("spring.rabbitmq.port",()->System.getenv("CF_RABBIT_PORT"));
        r.add("spring.rabbitmq.username",()->"test");r.add("spring.rabbitmq.password",()->System.getenv("CF_RABBIT_PASSWORD"));
    }
    @Autowired Db db;
    @Autowired NotificationRabbitRelay relay;
    @Autowired NotificationRabbitPublisher publisher;
    @Autowired NotificationOutboxWorker worker;
    @Autowired RabbitTemplate template;
    @Autowired AmqpAdmin admin;
    @MockBean WechatMessageSender sender;
    private long user,id;
    @BeforeEach void setup(){
        when(sender.enabled()).thenReturn(true);
        user=db.insert("sys_user",Db.fields("nickname","Rabbit测试","wechat_openid","synthetic-"+UUID.randomUUID()));
        id=db.insert("notification_outbox",Db.fields("event_key","test:"+UUID.randomUUID(),"recipient_id",user,"channel","WECHAT","payload","{}"));
    }
    @AfterEach void cleanup(){db.execute("DELETE FROM notification_outbox WHERE recipient_id=?",user);db.execute("DELETE FROM sys_user WHERE id=?",user);}
    private String status(){return (String)db.required("SELECT status FROM notification_outbox WHERE id=?",id).get("status");}
    private void waitStatus(String expected){await().atMost(Duration.ofSeconds(10)).until(()->expected.equals(status()));}
    @Test void confirmedPublishConsumesAndDuplicatesDoNotResend() throws Exception {
        relay.drain();waitStatus("SENT");
        publisher.publish(id);worker.deliverById(id);worker.deliverById(id);
        verify(sender,times(1)).send(anyString(),eq("{}"));
        assertEquals(1,Db.integer(db.required("SELECT attempts FROM notification_outbox WHERE id=?",id),"attempts"));
    }
    @Test void deliveryRetriesPersistAndStopAfterFiveFailures() throws Exception {
        doThrow(new java.io.IOException("synthetic failure")).when(sender).send(anyString(),anyString());
        for(int attempt=1;attempt<=5;attempt++){
            final int expected=attempt;
            relay.drain();
            await().atMost(Duration.ofSeconds(10)).until(()->{
                var row=db.required("SELECT status,attempts FROM notification_outbox WHERE id=?",id);
                return Db.integer(row,"attempts")==expected&&(expected==5?"FAILED":"PENDING").equals(row.get("status"));
            });
            db.execute("UPDATE notification_outbox SET next_retry_at=DATE_SUB(UTC_TIMESTAMP(3),INTERVAL 1 SECOND) WHERE id=?",id);
        }
        relay.drain();worker.deliverById(id);assertEquals("FAILED",status());verify(sender,times(5)).send(anyString(),anyString());
    }
    @Test void unroutablePublishDoesNotMarkSentAndCanRecover(){
        var binding=new Binding(NotificationRabbitConfiguration.QUEUE,Binding.DestinationType.QUEUE,NotificationRabbitConfiguration.EXCHANGE,"wechat",null);
        admin.removeBinding(binding);
        try {
            relay.drain();assertEquals("PENDING",status());
            var row=db.required("SELECT attempts,mq_last_error FROM notification_outbox WHERE id=?",id);
            assertEquals(0,Db.integer(row,"attempts"));assertEquals("MQ_PUBLISH_FAILED",row.get("mqLastError"));
        }finally {admin.declareBinding(binding);}
        db.execute("UPDATE notification_outbox SET mq_next_publish_at=NULL WHERE id=?",id);
        relay.drain();waitStatus("SENT");
    }
    @Test void invalidMessageGoesToDeadLetterQueue() throws Exception {
        admin.purgeQueue(NotificationRabbitConfiguration.DEAD);
        template.convertAndSend(NotificationRabbitConfiguration.EXCHANGE,"wechat","not-an-id");
        await().atMost(Duration.ofSeconds(10)).until(()->template.receive(NotificationRabbitConfiguration.DEAD)!=null);
        assertEquals("PENDING",status());verify(sender,never()).send(anyString(),anyString());
    }
    @Test void expiredDeliveryLeaseIsRecovered(){
        db.execute("UPDATE notification_outbox SET status='PROCESSING',attempts=1,locked_until=DATE_SUB(UTC_TIMESTAMP(3),INTERVAL 1 SECOND) WHERE id=?",id);
        relay.drain();waitStatus("SENT");
        assertEquals(2,Db.integer(db.required("SELECT attempts FROM notification_outbox WHERE id=?",id),"attempts"));
    }
}
