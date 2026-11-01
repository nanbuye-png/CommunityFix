package com.communityfix.module.notification;

import com.communityfix.common.Db;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.io.IOException;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@SpringBootTest
@EnabledIfEnvironmentVariable(named="CF_INTEGRATION",matches="true")
class NotificationOutboxIntegrationTest {
    @Autowired Db db;
    @Autowired WechatSubscriptionService subscriptions;
    @Autowired NotificationOutboxWorker worker;
    @MockitoBean WechatMessageSender sender;

    @Test void staleSnapshotCannotClaimAnExpiredNewerAttempt() throws Exception {
        long user = db.insert("sys_user", Db.fields("nickname", "租约测试", "wechat_openid", "test-" + UUID.randomUUID()));
        String event = "lease:" + UUID.randomUUID();
        try {
            db.execute("INSERT INTO notification_outbox(event_key,recipient_id,channel,payload) VALUES (?,?,'WECHAT','{}')", event, user);
            var old = db.required("SELECT id,attempts,payload,'synthetic' AS wechat_openid FROM notification_outbox WHERE event_key=?", event);
            db.execute("UPDATE notification_outbox SET status='PROCESSING',attempts=1,locked_until=DATE_SUB(UTC_TIMESTAMP(3),INTERVAL 1 SECOND) WHERE event_key=?", event);
            worker.deliver(old);
            verify(sender, never()).send(anyString(), anyString());
            assertEquals(1, db.required("SELECT attempts FROM notification_outbox WHERE event_key=?", event).get("attempts"));
            var fresh = db.required("SELECT id,attempts,payload,'synthetic' AS wechat_openid FROM notification_outbox WHERE event_key=?", event);
            new NotificationOutboxWorker(db, sender).deliver(fresh);
            assertEquals("SENT", db.required("SELECT status FROM notification_outbox WHERE event_key=?", event).get("status"));
            assertEquals(2, db.required("SELECT attempts FROM notification_outbox WHERE event_key=?", event).get("attempts"));
            verify(sender, times(1)).send(anyString(), anyString());
        } finally {
            db.execute("DELETE FROM notification_outbox WHERE event_key=?", event);
            db.execute("DELETE FROM sys_user WHERE id=?", user);
        }
    }

    @Test void concurrentWorkersDeliverOneClaimOnly() throws Exception {
        long user = db.insert("sys_user", Db.fields("nickname", "队列竞争", "wechat_openid", "test-" + UUID.randomUUID()));
        String event = "race:" + UUID.randomUUID();
        try {
            db.execute("INSERT INTO notification_outbox(event_key,recipient_id,channel,payload) VALUES (?,?,'WECHAT','{}')", event, user);
            var snapshot = db.required("SELECT id,attempts,payload,'synthetic' AS wechat_openid FROM notification_outbox WHERE event_key=?", event);
            var other = new NotificationOutboxWorker(db, sender);
            try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
                var gate = new java.util.concurrent.CountDownLatch(1);
                var first = pool.submit(() -> { gate.await(); worker.deliver(snapshot); return true; });
                var second = pool.submit(() -> { gate.await(); other.deliver(snapshot); return true; });
                gate.countDown(); first.get(20, java.util.concurrent.TimeUnit.SECONDS); second.get(20, java.util.concurrent.TimeUnit.SECONDS);
            }
            assertEquals("SENT", db.required("SELECT status FROM notification_outbox WHERE event_key=?", event).get("status"));
            assertEquals(1, db.required("SELECT attempts FROM notification_outbox WHERE event_key=?", event).get("attempts"));
            verify(sender, times(1)).send(anyString(), anyString());
        } finally {
            db.execute("DELETE FROM notification_outbox WHERE event_key=?", event);
            db.execute("DELETE FROM sys_user WHERE id=?", user);
        }
    }

    @Test void duplicateEventConsumesOneGrantAndFailedDeliveryRetries() throws Exception {
        when(sender.enabled()).thenReturn(true);
        when(sender.templateId()).thenReturn("test-template");
        long user = db.insert("sys_user", Db.fields("nickname", "通知集成测试", "wechat_openid", "test-" + UUID.randomUUID()));
        String event = "test:" + UUID.randomUUID();
        try {
            db.execute("INSERT INTO wechat_subscription_grant(user_id,template_id,remaining_count) VALUES (?,'test-template',1)", user);
            subscriptions.enqueueOrder(user, 123, event, "CF-TEST-123", "ASSIGNED");
            subscriptions.enqueueOrder(user, 123, event, "CF-TEST-123", "ASSIGNED");
            assertEquals(0, db.required("SELECT remaining_count FROM wechat_subscription_grant WHERE user_id=?", user).get("remainingCount"));
            assertEquals(1, db.count("SELECT COUNT(*) FROM notification_outbox WHERE event_key=?", event));

            doThrow(new IOException("temporary failure")).doNothing().when(sender).send(anyString(), anyString());
            var job = db.required("SELECT id,attempts,payload,(SELECT wechat_openid FROM sys_user WHERE id=?) AS wechat_openid " +
                    "FROM notification_outbox WHERE event_key=?", user, event);
            assertInstanceOf(String.class, job.get("payload"));
            worker.deliver(job);
            var retry = db.required("SELECT status,attempts,next_retry_at,last_error FROM notification_outbox WHERE event_key=?", event);
            assertEquals("PENDING", retry.get("status"));
            assertEquals(1, retry.get("attempts"));
            assertNotNull(retry.get("nextRetryAt"));
            assertTrue(((String)retry.get("lastError")).contains("temporary failure"));

            db.execute("UPDATE notification_outbox SET next_retry_at=DATE_SUB(UTC_TIMESTAMP(3),INTERVAL 1 SECOND) WHERE event_key=?", event);
            worker.deliver(db.required("SELECT id,attempts,payload,(SELECT wechat_openid FROM sys_user WHERE id=?) AS wechat_openid " +
                    "FROM notification_outbox WHERE event_key=?", user, event));
            var sent = db.required("SELECT status,attempts,last_error FROM notification_outbox WHERE event_key=?", event);
            assertEquals("SENT", sent.get("status"));
            assertEquals(2, sent.get("attempts"));
            assertNull(sent.get("lastError"));
        } finally {
            db.execute("DELETE FROM notification_outbox WHERE event_key=?", event);
            db.execute("DELETE FROM wechat_subscription_grant WHERE user_id=?", user);
            db.execute("DELETE FROM sys_user WHERE id=?", user);
        }
    }

    @Test void fifthFailureStopsAutomaticRetries() throws Exception {
        when(sender.enabled()).thenReturn(true);
        long user = db.insert("sys_user", Db.fields("nickname", "通知失败测试", "wechat_openid", "test-" + UUID.randomUUID()));
        String event = "test:" + UUID.randomUUID();
        try {
            db.execute("INSERT INTO notification_outbox(event_key,recipient_id,channel,payload) VALUES (?,?,'WECHAT',?)",
                    event, user, "{\"orderNo\":\"CF-TEST\",\"status\":\"待接单\",\"page\":\"pages/detail/index?id=1\"}");
            doThrow(new IOException("temporary failure")).when(sender).send(anyString(), anyString());
            for (int i = 1; i <= 5; i++) {
                worker.deliver(db.required("SELECT id,attempts,payload,(SELECT wechat_openid FROM sys_user WHERE id=?) AS wechat_openid " +
                        "FROM notification_outbox WHERE event_key=?", user, event));
                var state = db.required("SELECT status,attempts,next_retry_at FROM notification_outbox WHERE event_key=?", event);
                assertEquals(i, state.get("attempts"));
                assertEquals(i == 5 ? "FAILED" : "PENDING", state.get("status"));
                if (i < 5) db.execute("UPDATE notification_outbox SET next_retry_at=DATE_SUB(UTC_TIMESTAMP(3),INTERVAL 1 SECOND) WHERE event_key=?", event);
                else assertNull(state.get("nextRetryAt"));
            }
        } finally {
            db.execute("DELETE FROM notification_outbox WHERE event_key=?", event);
            db.execute("DELETE FROM sys_user WHERE id=?", user);
        }
    }
}
