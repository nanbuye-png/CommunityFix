package com.communityfix.module.notification;

import com.communityfix.common.Db;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class NotificationOutboxWorker {
    private static final Logger log = LoggerFactory.getLogger(NotificationOutboxWorker.class);
    private final Db db;
    private final WechatMessageSender sender;

    public NotificationOutboxWorker(Db db, WechatMessageSender sender) {
        this.db = db;
        this.sender = sender;
    }

    @Scheduled(fixedDelay = 60000, initialDelay = 60000)
    public void drain() {
        if (!sender.enabled()) return;
        var jobs = db.rows("SELECT o.id,o.attempts,o.payload,u.wechat_openid FROM notification_outbox o " +
                "JOIN sys_user u ON u.id=o.recipient_id WHERE o.channel='WECHAT' AND " +
                "((o.status='PENDING' AND (o.next_retry_at IS NULL OR o.next_retry_at<=UTC_TIMESTAMP(3))) " +
                "OR (o.status='PROCESSING' AND o.locked_until<=UTC_TIMESTAMP(3))) ORDER BY o.id LIMIT 50");
        for (var job : jobs) deliver(job);
    }

    void deliver(Map<String, Object> job) {
        long id = Db.id(job, "id");
        int claimed = db.execute("UPDATE notification_outbox SET status='PROCESSING',attempts=attempts+1," +
                "locked_until=DATE_ADD(UTC_TIMESTAMP(3),INTERVAL 2 MINUTE),updated_at=UTC_TIMESTAMP(3) " +
                "WHERE id=? AND attempts=? AND channel='WECHAT' AND ((status='PENDING' AND (next_retry_at IS NULL OR next_retry_at<=UTC_TIMESTAMP(3))) " +
                "OR (status='PROCESSING' AND locked_until<=UTC_TIMESTAMP(3)))", id, Db.integer(job, "attempts"));
        if (claimed == 0) return;
        int attempt = Db.integer(job, "attempts") + 1;
        try {
            if (job.get("wechatOpenid") == null) throw new IllegalStateException("WeChat account not linked");
            sender.send((String) job.get("wechatOpenid"), (String) job.get("payload"));
            db.execute("UPDATE notification_outbox SET status='SENT',locked_until=NULL,next_retry_at=NULL,last_error=NULL," +
                    "updated_at=UTC_TIMESTAMP(3) WHERE id=? AND status='PROCESSING' AND attempts=?", id, attempt);
        } catch (Exception ex) {
            String error = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
            if (error.length() > 500) error = error.substring(0, 500);
            int delay = Math.min(3600, 30 * (1 << Math.min(attempt - 1, 6)));
            db.execute("UPDATE notification_outbox SET status=?,locked_until=NULL," +
                    "next_retry_at=CASE WHEN ? >= 5 THEN NULL ELSE DATE_ADD(UTC_TIMESTAMP(3),INTERVAL ? SECOND) END," +
                    "last_error=?,updated_at=UTC_TIMESTAMP(3) WHERE id=? AND status='PROCESSING' AND attempts=?",
                    attempt >= 5 ? "FAILED" : "PENDING", attempt, delay, error, id, attempt);
            log.warn("WeChat delivery attempt {} failed for outbox {}", attempt, id);
        }
    }
}
