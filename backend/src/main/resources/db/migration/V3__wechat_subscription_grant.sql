CREATE TABLE wechat_subscription_grant (
 user_id BIGINT NOT NULL,
 template_id VARCHAR(100) NOT NULL,
 remaining_count INT NOT NULL DEFAULT 0,
 updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 PRIMARY KEY(user_id,template_id),
 FOREIGN KEY(user_id) REFERENCES sys_user(id),
 CHECK(remaining_count BETWEEN 0 AND 20)
);
