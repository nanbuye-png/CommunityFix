package com.communityfix.module.notification;

import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import java.nio.charset.StandardCharsets;

@Component
@ConditionalOnProperty(name="communityfix.rabbit.enabled",havingValue="true")
public class NotificationRabbitConsumer {
    private final NotificationOutboxWorker worker;
    public NotificationRabbitConsumer(NotificationOutboxWorker worker){this.worker=worker;}
    @RabbitListener(queues=NotificationRabbitConfiguration.QUEUE)
    public void receive(Message message){
        long id;
        try {
            if(message.getBody().length>19)throw new IllegalArgumentException();
            String body=new String(message.getBody(),StandardCharsets.UTF_8);
            if(!body.matches("[1-9][0-9]{0,18}"))throw new IllegalArgumentException();
            id=Long.parseLong(body);
        }catch(IllegalArgumentException e){throw new AmqpRejectAndDontRequeueException("INVALID_NOTIFICATION_ID");}
        worker.deliverById(id);
        // Container AUTO ACK occurs only after database outcome has been saved.
    }
}
