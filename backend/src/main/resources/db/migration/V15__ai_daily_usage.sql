CREATE TABLE ai_daily_usage (
 usage_date DATE NOT NULL,
 scene VARCHAR(20) NOT NULL,
 requests BIGINT NOT NULL DEFAULT 0,
 reported_tokens BIGINT NOT NULL DEFAULT 0,
 unknown_usage BIGINT NOT NULL DEFAULT 0,
 alerted BOOLEAN NOT NULL DEFAULT FALSE,
 PRIMARY KEY(usage_date,scene)
);
