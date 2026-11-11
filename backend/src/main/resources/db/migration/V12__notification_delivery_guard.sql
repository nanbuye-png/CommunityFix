ALTER TABLE notification_outbox ADD COLUMN send_started BOOLEAN NOT NULL DEFAULT FALSE;
-- Existing attempts may already have reached WeChat; never refund them speculatively.
UPDATE notification_outbox SET send_started=TRUE WHERE attempts>0 OR status IN ('SENT','FAILED','PROCESSING');
