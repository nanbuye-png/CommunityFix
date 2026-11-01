CREATE TABLE ai_request_permit (
 id BIGINT PRIMARY KEY AUTO_INCREMENT,
 user_id BIGINT NOT NULL,
 requested_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 INDEX(user_id,requested_at), INDEX(requested_at),
 FOREIGN KEY(user_id) REFERENCES sys_user(id)
);
CREATE TABLE ai_circuit_state (
 provider_key CHAR(64) PRIMARY KEY,
 failure_count INT NOT NULL DEFAULT 0,
 generation BIGINT NOT NULL DEFAULT 0,
 open_until DATETIME(3),
 probe_until DATETIME(3),
 updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 CHECK(failure_count>=0)
);
