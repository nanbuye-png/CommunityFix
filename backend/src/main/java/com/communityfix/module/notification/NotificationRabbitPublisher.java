package com.communityfix.module.notification;

import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Component
@ConditionalOnProperty(name="communityfix.rabbit.enabled",havingValue="true")
public class NotificationRabbitPublisher {
    private final RabbitTemplate template;
    public NotificationRabbitPublisher(RabbitTemplate template){this.template=template;}
    public void publish(long id) throws Exception {
        var properties=new MessageProperties();properties.setContentType("text/plain");
        properties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);properties.setMessageId(Long.toString(id));
        var correlation=new CorrelationData(UUID.randomUUID().toString());
        template.send(NotificationRabbitConfiguration.EXCHANGE,"wechat",
            new Message(Long.toString(id).getBytes(StandardCharsets.UTF_8),properties),correlation);
        var confirm=correlation.getFuture().get(5,TimeUnit.SECONDS);
        if(!confirm.isAck()||correlation.getReturned()!=null)throw new IllegalStateException("MQ_PUBLISH_REJECTED");
    }
}
