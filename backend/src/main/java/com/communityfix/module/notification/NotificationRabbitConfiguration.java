package com.communityfix.module.notification;

import org.springframework.amqp.core.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;

@Configuration
@ConditionalOnProperty(name="communityfix.rabbit.enabled",havingValue="true")
public class NotificationRabbitConfiguration {
    public static final String EXCHANGE="communityfix.notification.v1";
    public static final String QUEUE="communityfix.notification.wechat.v1";
    public static final String DEAD="communityfix.notification.dead.v1";
    @Bean public Declarables notificationTopology(){
        return new Declarables(new DirectExchange(EXCHANGE,true,false),new DirectExchange(DEAD,true,false),
            QueueBuilder.durable(QUEUE).deadLetterExchange(DEAD).deadLetterRoutingKey("failed").build(),
            QueueBuilder.durable(DEAD).build(),
            new Binding(QUEUE,Binding.DestinationType.QUEUE,EXCHANGE,"wechat",null),
            new Binding(DEAD,Binding.DestinationType.QUEUE,DEAD,"failed",null));
    }
}
