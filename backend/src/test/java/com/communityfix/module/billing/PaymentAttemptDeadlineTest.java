package com.communityfix.module.billing;

import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PaymentAttemptDeadlineTest {
    @Test void deadlineIsExclusiveAtDatabaseMillisecondPrecision() {
        var deadline = Instant.parse("2026-11-06T02:30:00Z");
        var pending = Map.<String,Object>of("status","PENDING","expiresAt",deadline);
        assertFalse(BillingService.isExpired(pending,deadline.minusMillis(1)));
        assertTrue(BillingService.isExpired(pending,deadline));
        assertTrue(BillingService.isExpired(pending,deadline.plusMillis(1)));
    }

    @Test void disabledMockPaymentsRejectBothOperationsBeforeOpeningAnAttempt() {
        var service = new BillingService(null,null,null,null,null,null,false,true);
        assertEquals("MOCK_PAYMENT_DISABLED",assertThrows(com.communityfix.common.ApiException.class,
            () -> service.startPayment(1,new BillingController.StartPayment(1),"test-start")).errorCode);
        assertEquals("MOCK_PAYMENT_DISABLED",assertThrows(com.communityfix.common.ApiException.class,
            () -> service.pay(1,new BillingController.Pay(1,"mock"),"test-confirm")).errorCode);
    }
}
