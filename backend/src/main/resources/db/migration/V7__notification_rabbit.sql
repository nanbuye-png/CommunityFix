ALTER TABLE notification_outbox
 ADD COLUMN mq_next_publish_at DATETIME(3) NULL,
 ADD COLUMN mq_publish_attempts INT NOT NULL DEFAULT 0,
 ADD COLUMN mq_last_error VARCHAR(64) NULL,
 ADD INDEX idx_outbox_mq(channel,status,mq_next_publish_at);
