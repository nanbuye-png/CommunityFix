package com.communityfix.module.notification;

import com.communityfix.common.Db;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name="communityfix.rabbit.enabled",havingValue="true")
public class NotificationRabbitRelay {
    private final Db db;private final NotificationRabbitPublisher publisher;private final WechatMessageSender sender;
    public NotificationRabbitRelay(Db db,NotificationRabbitPublisher publisher,WechatMessageSender sender){this.db=db;this.publisher=publisher;this.sender=sender;}
    private static final String READY="channel='WECHAT' AND (mq_next_publish_at IS NULL OR mq_next_publish_at<=UTC_TIMESTAMP(3)) AND "
        +"((status='PENDING' AND (next_retry_at IS NULL OR next_retry_at<=UTC_TIMESTAMP(3))) OR (status='PROCESSING' AND locked_until<=UTC_TIMESTAMP(3)))";
    @Scheduled(fixedDelayString="${communityfix.rabbit.relay-delay-ms:5000}",initialDelayString="${communityfix.rabbit.relay-initial-delay-ms:5000}")
    public void drain(){
        if(!sender.enabled())return;
        for(var row:db.rows("SELECT id,mq_publish_attempts FROM notification_outbox WHERE "+READY+" ORDER BY id LIMIT 50")){
            long id=Db.id(row,"id");int generation=Db.integer(row,"mqPublishAttempts");
            if(db.execute("UPDATE notification_outbox SET mq_next_publish_at=DATE_ADD(UTC_TIMESTAMP(3),INTERVAL 2 MINUTE),mq_publish_attempts=mq_publish_attempts+1 WHERE id=? AND mq_publish_attempts=? AND "+READY,id,generation)==0)continue;
            try {
                publisher.publish(id);
                db.execute("UPDATE notification_outbox SET mq_last_error=NULL WHERE id=? AND mq_publish_attempts=?",id,generation+1);
            }catch(Exception e){
                if(e instanceof InterruptedException)Thread.currentThread().interrupt();
                db.execute("UPDATE notification_outbox SET mq_last_error='MQ_PUBLISH_FAILED',mq_next_publish_at=DATE_ADD(UTC_TIMESTAMP(3),INTERVAL 30 SECOND) WHERE id=? AND mq_publish_attempts=?",id,generation+1);
                // Bound outage work; remaining rows are picked up by the next scan.
                break;
            }
        }
    }
}
