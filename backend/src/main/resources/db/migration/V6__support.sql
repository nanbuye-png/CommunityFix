CREATE TABLE support_session (
 id BIGINT PRIMARY KEY AUTO_INCREMENT, user_id BIGINT NOT NULL, user_role VARCHAR(20) NOT NULL,
 community_id BIGINT NULL, order_id BIGINT NULL, status VARCHAR(20) NOT NULL DEFAULT 'AI', agent_id BIGINT NULL,
 version INT NOT NULL DEFAULT 0, event_seq BIGINT NOT NULL DEFAULT 0, ai_generation BIGINT NOT NULL DEFAULT 0,
 created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3), updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3), closed_at DATETIME(3),
 FOREIGN KEY(user_id) REFERENCES sys_user(id), FOREIGN KEY(community_id) REFERENCES community(id),
 FOREIGN KEY(order_id) REFERENCES repair_order(id), FOREIGN KEY(agent_id) REFERENCES sys_user(id),
 INDEX idx_support_queue(status,community_id,updated_at), INDEX idx_support_user(user_id,updated_at)
);
CREATE TABLE support_message (
 id BIGINT PRIMARY KEY AUTO_INCREMENT, session_id BIGINT NOT NULL, seq BIGINT NOT NULL, sender_id BIGINT NULL,
 sender_type VARCHAR(20) NOT NULL, client_id VARCHAR(80) NULL, content VARCHAR(2000) NOT NULL, metadata_json TEXT NULL,
 created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 FOREIGN KEY(session_id) REFERENCES support_session(id) ON DELETE CASCADE,
 UNIQUE KEY uk_support_sequence(session_id,seq), UNIQUE KEY uk_support_message(session_id,sender_id,client_id)
);
CREATE TABLE support_read (
 session_id BIGINT NOT NULL, user_id BIGINT NOT NULL, last_seq BIGINT NOT NULL DEFAULT 0,
 PRIMARY KEY(session_id,user_id), FOREIGN KEY(session_id) REFERENCES support_session(id) ON DELETE CASCADE
);
CREATE TABLE support_agent (
 user_id BIGINT PRIMARY KEY, online BOOLEAN NOT NULL DEFAULT FALSE, heartbeat_at DATETIME(3) NOT NULL,
 FOREIGN KEY(user_id) REFERENCES sys_user(id)
);
CREATE TABLE support_knowledge (
 id BIGINT PRIMARY KEY AUTO_INCREMENT, community_id BIGINT NULL, title VARCHAR(100) NOT NULL,
 keywords VARCHAR(300) NOT NULL, question VARCHAR(500) NOT NULL, answer VARCHAR(2000) NOT NULL,
 audience VARCHAR(20) NOT NULL DEFAULT 'ALL', status VARCHAR(20) NOT NULL DEFAULT 'DRAFT', version INT NOT NULL DEFAULT 0,
 updated_by BIGINT NOT NULL, updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 FOREIGN KEY(community_id) REFERENCES community(id), INDEX idx_support_knowledge(community_id,status)
);
CREATE TABLE support_ai_job (
 id BIGINT PRIMARY KEY AUTO_INCREMENT, session_id BIGINT NOT NULL, message_id BIGINT NOT NULL,
 generation BIGINT NOT NULL, status VARCHAR(20) NOT NULL DEFAULT 'PENDING', lease_token VARCHAR(36), locked_until DATETIME(3),
 attempts INT NOT NULL DEFAULT 0, duration_ms BIGINT NULL, error_code VARCHAR(50), created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 FOREIGN KEY(session_id) REFERENCES support_session(id) ON DELETE CASCADE, UNIQUE KEY uk_support_job(message_id), INDEX idx_support_job(status,locked_until)
);
ALTER TABLE notification ADD COLUMN support_session_id BIGINT NULL;
INSERT INTO support_knowledge(title,keywords,question,answer,audience,status,updated_by)
 SELECT '如何注册与绑定房屋','注册 账号 居民 绑定 房屋','居民如何开始报修？','在登录页选择居民入口并创建账号，进入房屋绑定选择小区和房屋，提交后等待物业审核。审核通过后可提交报修。','RESIDENT','PUBLISHED',id FROM sys_user WHERE username='admin' LIMIT 1;
