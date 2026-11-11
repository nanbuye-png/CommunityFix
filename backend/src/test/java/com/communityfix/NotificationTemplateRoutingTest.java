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
    @Test void onlyUnpaidBillTriggersPaymentReminder(){
        var db=mock(Db.class);var subscriptions=mock(WechatSubscriptionService.class);var notifications=new NotificationService(db,subscriptions);
        when(db.row(startsWith("SELECT id,amount_fen FROM repair_bill"),eq(31L))).thenReturn(null);
        var order=Db.fields("id",31L,"version",9,"residentId",11L,"communityId",1L,"orderNo","CF31","status","CLOSED");
        notifications.order(order,"confirm",null);verify(subscriptions,never()).enqueuePaymentDue(anyMap(),anyLong(),anyLong());
        when(db.row(startsWith("SELECT id,amount_fen FROM repair_bill"),eq(31L))).thenReturn(Db.fields("id",41L,"amountFen",12345L));
        notifications.order(order,"confirm",null);verify(subscriptions).enqueuePaymentDue(order,41L,12345L);
    }
    @Test void acceptanceTemplateOnlyQueuesForResidentAfterWorkerAccepts() {
        Db db = mock(Db.class);
        when(db.row(startsWith("SELECT id,amount_fen FROM repair_bill"),eq(31L))).thenReturn(null);
        WechatSubscriptionService subscriptions = mock(WechatSubscriptionService.class);
        when(db.rows(startsWith("SELECT user_id FROM admin_community"), any())).thenReturn(List.of(Map.of("userId", 12L)));
        when(db.required(startsWith("SELECT user_id FROM worker_profile"), any())).thenReturn(Map.of("userId", 13L));
        NotificationService notifications = new NotificationService(db, subscriptions);
        Map<String,Object> order = Db.fields("id", 31L, "version", 3, "residentId", 11L,
                "communityId", 1L, "workerId", 7L, "orderNo", "CF-31", "status", "ACCEPTED");

        notifications.order(order, "create", null);

        verifyNoInteractions(subscriptions);

        notifications.order(order, "accept", null);
        verify(subscriptions).enqueueAcceptance(order, "order:31:3:accept");
        notifications.order(order,"finish",null);
        verify(subscriptions).enqueueCompletion(order,"order:31:3:finish");
    }
}
