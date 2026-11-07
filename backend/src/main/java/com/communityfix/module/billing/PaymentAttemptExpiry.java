package com.communityfix.module.billing;

import com.communityfix.common.Db;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class PaymentAttemptExpiry {
    private final Db db;

    public PaymentAttemptExpiry(Db db) {
        this.db = db;
    }

    @Scheduled(fixedDelayString = "${communityfix.billing.expiry-scan-ms:60000}",
               initialDelayString = "${communityfix.billing.expiry-scan-ms:60000}")
    public void closeExpired() {
        int updated;
        do {
            updated = db.execute("""
                UPDATE repair_payment_attempt SET status='EXPIRED'
                WHERE status='PENDING' AND expires_at<=UTC_TIMESTAMP(3)
                ORDER BY expires_at,id LIMIT 200
                """);
        } while (updated == 200);
    }
}
