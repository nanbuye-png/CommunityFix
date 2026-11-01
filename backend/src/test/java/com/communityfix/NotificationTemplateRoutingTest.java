package com.communityfix;

import com.communityfix.common.Db;
import com.communityfix.module.notification.NotificationService;
import com.communityfix.module.notification.WechatSubscriptionService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class NotificationTemplateRoutingTest {
    @Test void acceptanceTemplateOnlyQueuesForResidentAfterWorkerAccepts() {
        Db db = mock(Db.class);
        WechatSubscriptionService subscriptions = mock(WechatSubscriptionService.class);
        when(db.rows(startsWith("SELECT user_id FROM admin_community"), any())).thenReturn(List.of(Map.of("userId", 12L)));
        when(db.required(startsWith("SELECT user_id FROM worker_profile"), any())).thenReturn(Map.of("userId", 13L));
        NotificationService notifications = new NotificationService(db, subscriptions);
        Map<String,Object> order = Db.fields("id", 31L, "version", 3, "residentId", 11L,
                "communityId", 1L, "workerId", 7L, "orderNo", "CF-31", "status", "ACCEPTED");

        notifications.order(order, "create", null);
        notifications.order(order, "finish", null);
        verifyNoInteractions(subscriptions);

        notifications.order(order, "accept", null);
        verify(subscriptions).enqueueOrder(11L, 31L, "order:31:3:accept", "CF-31", "已接单");
    }
}
