CREATE TABLE sys_user (
 id BIGINT PRIMARY KEY AUTO_INCREMENT, username VARCHAR(50) UNIQUE, password VARCHAR(255),
 nickname VARCHAR(50) NOT NULL, phone VARCHAR(20), wechat_openid VARCHAR(100) UNIQUE,
 status VARCHAR(16) NOT NULL DEFAULT 'ENABLED', token_version INT NOT NULL DEFAULT 0,
 created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3), updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
);
CREATE TABLE sys_role (id BIGINT PRIMARY KEY AUTO_INCREMENT, role_name VARCHAR(50) NOT NULL, role_code VARCHAR(50) NOT NULL UNIQUE);
CREATE TABLE sys_permission (id BIGINT PRIMARY KEY AUTO_INCREMENT, permission_code VARCHAR(100) NOT NULL UNIQUE, name VARCHAR(100) NOT NULL);
CREATE TABLE sys_user_role (user_id BIGINT NOT NULL, role_id BIGINT NOT NULL, PRIMARY KEY(user_id,role_id), FOREIGN KEY(user_id) REFERENCES sys_user(id), FOREIGN KEY(role_id) REFERENCES sys_role(id));
CREATE TABLE sys_role_permission (role_id BIGINT NOT NULL, permission_id BIGINT NOT NULL, PRIMARY KEY(role_id,permission_id), FOREIGN KEY(role_id) REFERENCES sys_role(id), FOREIGN KEY(permission_id) REFERENCES sys_permission(id));
CREATE TABLE community (
 id BIGINT PRIMARY KEY AUTO_INCREMENT, name VARCHAR(100) NOT NULL, address VARCHAR(255) NOT NULL,
 description VARCHAR(1000), status VARCHAR(16) NOT NULL DEFAULT 'ENABLED',
 created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3), updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
);
CREATE TABLE admin_community (user_id BIGINT NOT NULL, community_id BIGINT NOT NULL, PRIMARY KEY(user_id,community_id), FOREIGN KEY(user_id) REFERENCES sys_user(id), FOREIGN KEY(community_id) REFERENCES community(id));
CREATE TABLE building (
 id BIGINT PRIMARY KEY AUTO_INCREMENT, community_id BIGINT NOT NULL, building_no VARCHAR(20) NOT NULL, status VARCHAR(16) NOT NULL DEFAULT 'ENABLED',
 created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3), updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 UNIQUE(community_id,building_no), FOREIGN KEY(community_id) REFERENCES community(id)
);
CREATE TABLE unit (
 id BIGINT PRIMARY KEY AUTO_INCREMENT, building_id BIGINT NOT NULL, unit_no VARCHAR(20) NOT NULL, status VARCHAR(16) NOT NULL DEFAULT 'ENABLED',
 created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3), updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 UNIQUE(building_id,unit_no), FOREIGN KEY(building_id) REFERENCES building(id)
);
CREATE TABLE room (
 id BIGINT PRIMARY KEY AUTO_INCREMENT, unit_id BIGINT NOT NULL, room_no VARCHAR(20) NOT NULL, floor INT NOT NULL, status VARCHAR(16) NOT NULL DEFAULT 'ENABLED',
 created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3), updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 UNIQUE(unit_id,room_no), FOREIGN KEY(unit_id) REFERENCES unit(id)
);
CREATE TABLE resident_room_binding (
 id BIGINT PRIMARY KEY AUTO_INCREMENT, user_id BIGINT NOT NULL, room_id BIGINT NOT NULL, status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
 reviewer_id BIGINT, reviewed_at DATETIME(3), review_reason VARCHAR(500),
 created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3), updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 UNIQUE(user_id,room_id), INDEX(user_id,status), FOREIGN KEY(user_id) REFERENCES sys_user(id), FOREIGN KEY(room_id) REFERENCES room(id), FOREIGN KEY(reviewer_id) REFERENCES sys_user(id),
 CHECK(status IN ('PENDING','APPROVED','REJECTED','REVOKED'))
);
CREATE TABLE resident_room_binding_log (
 id BIGINT PRIMARY KEY AUTO_INCREMENT, binding_id BIGINT NOT NULL, old_status VARCHAR(16), new_status VARCHAR(16) NOT NULL, operator_id BIGINT NOT NULL, reason VARCHAR(500),
 created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3), FOREIGN KEY(binding_id) REFERENCES resident_room_binding(id), FOREIGN KEY(operator_id) REFERENCES sys_user(id)
);
CREATE TABLE repair_category (
 id BIGINT PRIMARY KEY AUTO_INCREMENT, code VARCHAR(32) NOT NULL UNIQUE, name VARCHAR(50) NOT NULL, status VARCHAR(16) NOT NULL DEFAULT 'ENABLED', sort_order INT NOT NULL DEFAULT 0
);
CREATE TABLE worker_profile (
 id BIGINT PRIMARY KEY AUTO_INCREMENT, user_id BIGINT NOT NULL UNIQUE, real_name VARCHAR(50) NOT NULL,
 work_status VARCHAR(16) NOT NULL DEFAULT 'AVAILABLE', max_active_orders INT NOT NULL DEFAULT 5, score DECIMAL(3,2),
 created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3), updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 FOREIGN KEY(user_id) REFERENCES sys_user(id), CHECK(max_active_orders > 0), CHECK(work_status IN ('AVAILABLE','ON_LEAVE','DISABLED'))
);
CREATE TABLE worker_skill (
 id BIGINT PRIMARY KEY AUTO_INCREMENT, worker_id BIGINT NOT NULL, category_id BIGINT NOT NULL, proficiency DECIMAL(5,4) NOT NULL DEFAULT 1,
 UNIQUE(worker_id,category_id), FOREIGN KEY(worker_id) REFERENCES worker_profile(id), FOREIGN KEY(category_id) REFERENCES repair_category(id), CHECK(proficiency BETWEEN 0 AND 1)
);
CREATE TABLE worker_service_community (worker_id BIGINT NOT NULL, community_id BIGINT NOT NULL, PRIMARY KEY(worker_id,community_id), FOREIGN KEY(worker_id) REFERENCES worker_profile(id), FOREIGN KEY(community_id) REFERENCES community(id));
CREATE TABLE sla_rule (
 id BIGINT PRIMARY KEY AUTO_INCREMENT, priority VARCHAR(16) NOT NULL UNIQUE, response_minutes INT NOT NULL, completion_minutes INT NOT NULL, enabled TINYINT NOT NULL DEFAULT 1,
 CHECK(priority IN ('NORMAL','HIGH','URGENT')), CHECK(response_minutes > 0 AND completion_minutes > 0)
);
CREATE TABLE file_asset (
 id BIGINT PRIMARY KEY AUTO_INCREMENT, uploader_id BIGINT NOT NULL, object_key VARCHAR(255) NOT NULL UNIQUE,
 content_type VARCHAR(50) NOT NULL, size_bytes BIGINT NOT NULL, status VARCHAR(16) NOT NULL DEFAULT 'TEMP', expires_at DATETIME(3),
 created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3), updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3), FOREIGN KEY(uploader_id) REFERENCES sys_user(id)
);
CREATE TABLE ai_analysis_record (
 id BIGINT PRIMARY KEY AUTO_INCREMENT, user_id BIGINT NOT NULL, order_id BIGINT, request_id VARCHAR(64) NOT NULL,
 input_text TEXT NOT NULL, category_result BIGINT, priority_result VARCHAR(16), summary VARCHAR(2000), model_name VARCHAR(100),
 status VARCHAR(16) NOT NULL, duration_ms INT NOT NULL, error_code VARCHAR(64), adopted_result JSON,
 created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3), FOREIGN KEY(user_id) REFERENCES sys_user(id)
);
CREATE TABLE repair_order (
 id BIGINT PRIMARY KEY AUTO_INCREMENT, order_no VARCHAR(32) NOT NULL UNIQUE, resident_id BIGINT NOT NULL,
 community_id BIGINT NOT NULL, room_id BIGINT NOT NULL, category_id BIGINT NOT NULL, title VARCHAR(100) NOT NULL, description VARCHAR(2000) NOT NULL,
 priority VARCHAR(16) NOT NULL, status VARCHAR(32) NOT NULL, worker_id BIGINT, current_assignment_id BIGINT,
 version INT NOT NULL DEFAULT 0, round_no INT NOT NULL DEFAULT 1, reviewer_id BIGINT, review_reason VARCHAR(500), cancel_reason VARCHAR(500),
 reviewed_at DATETIME(3), appointment_start_at DATETIME(3), appointment_end_at DATETIME(3),
 accepted_at DATETIME(3), started_at DATETIME(3), finished_at DATETIME(3), closed_at DATETIME(3), canceled_at DATETIME(3),
 status_changed_at DATETIME(3) NOT NULL, response_started_at DATETIME(3) NOT NULL, response_deadline_at DATETIME(3), completion_deadline_at DATETIME(3),
 sla_rule_id BIGINT NOT NULL, response_minutes_snapshot INT NOT NULL, completion_minutes_snapshot INT NOT NULL,
 created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3), updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 FOREIGN KEY(resident_id) REFERENCES sys_user(id), FOREIGN KEY(community_id) REFERENCES community(id), FOREIGN KEY(room_id) REFERENCES room(id),
 FOREIGN KEY(category_id) REFERENCES repair_category(id), FOREIGN KEY(worker_id) REFERENCES worker_profile(id), FOREIGN KEY(reviewer_id) REFERENCES sys_user(id), FOREIGN KEY(sla_rule_id) REFERENCES sla_rule(id),
 INDEX(community_id,status,created_at,id), INDEX(resident_id,created_at,id), INDEX(worker_id,status,created_at,id), INDEX(status,response_deadline_at), INDEX(status,completion_deadline_at),
 CHECK(priority IN ('NORMAL','HIGH','URGENT')), CHECK(status IN ('SUBMITTED','REJECTED','PENDING_DISPATCH','ASSIGNED','ACCEPTED','IN_PROGRESS','PENDING_CONFIRM','CLOSED','CANCELED'))
);
ALTER TABLE ai_analysis_record ADD FOREIGN KEY(order_id) REFERENCES repair_order(id);
CREATE TABLE dispatch_recommendation (
 id BIGINT PRIMARY KEY AUTO_INCREMENT, order_id BIGINT NOT NULL, worker_id BIGINT NOT NULL, order_version INT NOT NULL,
 score DECIMAL(7,4) NOT NULL, factor_scores JSON NOT NULL, reason VARCHAR(500) NOT NULL, strategy_version VARCHAR(32) NOT NULL, batch_id VARCHAR(64) NOT NULL,
 created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3), UNIQUE(batch_id,worker_id), FOREIGN KEY(order_id) REFERENCES repair_order(id), FOREIGN KEY(worker_id) REFERENCES worker_profile(id)
);
CREATE TABLE repair_assignment (
 id BIGINT PRIMARY KEY AUTO_INCREMENT, order_id BIGINT NOT NULL, worker_id BIGINT NOT NULL, round_no INT NOT NULL, assigned_by BIGINT NOT NULL,
 dispatch_type VARCHAR(16) NOT NULL, score DECIMAL(7,4), status VARCHAR(16) NOT NULL, reason VARCHAR(500), recommendation_id BIGINT,
 assigned_at DATETIME(3) NOT NULL, accepted_at DATETIME(3), ended_at DATETIME(3),
 created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3), updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 INDEX(order_id,round_no,assigned_at), INDEX(worker_id,status), FOREIGN KEY(order_id) REFERENCES repair_order(id), FOREIGN KEY(worker_id) REFERENCES worker_profile(id), FOREIGN KEY(assigned_by) REFERENCES sys_user(id), FOREIGN KEY(recommendation_id) REFERENCES dispatch_recommendation(id),
 CHECK(status IN ('PENDING','ACCEPTED','REJECTED','REVOKED','FINISHED')), CHECK(dispatch_type IN ('MANUAL','SMART'))
);
ALTER TABLE repair_order ADD FOREIGN KEY(current_assignment_id) REFERENCES repair_assignment(id);
CREATE TABLE repair_order_media (
 id BIGINT PRIMARY KEY AUTO_INCREMENT, order_id BIGINT NOT NULL, file_id BIGINT NOT NULL UNIQUE, type VARCHAR(16) NOT NULL, round_no INT NOT NULL,
 created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3), FOREIGN KEY(order_id) REFERENCES repair_order(id), FOREIGN KEY(file_id) REFERENCES file_asset(id), CHECK(type IN ('FAULT','RESULT'))
);
CREATE TABLE repair_order_log (
 id BIGINT PRIMARY KEY AUTO_INCREMENT, order_id BIGINT NOT NULL, old_status VARCHAR(32), new_status VARCHAR(32) NOT NULL,
 operator_id BIGINT NOT NULL, remark VARCHAR(500), action VARCHAR(32) NOT NULL, round_no INT NOT NULL,
 old_version INT, new_version INT NOT NULL, request_id VARCHAR(64) NOT NULL,
 created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3), UNIQUE(order_id,new_version), FOREIGN KEY(order_id) REFERENCES repair_order(id), FOREIGN KEY(operator_id) REFERENCES sys_user(id)
);
CREATE TABLE repair_work_record (
 id BIGINT PRIMARY KEY AUTO_INCREMENT, order_id BIGINT NOT NULL, worker_id BIGINT NOT NULL, round_no INT NOT NULL,
 assignment_id BIGINT NOT NULL, record_type VARCHAR(16) NOT NULL, content VARCHAR(2000) NOT NULL, finished_at DATETIME(3),
 result_round_no INT GENERATED ALWAYS AS (CASE WHEN record_type='RESULT' THEN round_no ELSE NULL END) STORED,
 created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3), UNIQUE(order_id,result_round_no),
 FOREIGN KEY(order_id) REFERENCES repair_order(id), FOREIGN KEY(worker_id) REFERENCES worker_profile(id), FOREIGN KEY(assignment_id) REFERENCES repair_assignment(id), CHECK(record_type IN ('PROGRESS','RESULT'))
);
CREATE TABLE repair_evaluation (
 id BIGINT PRIMARY KEY AUTO_INCREMENT, order_id BIGINT NOT NULL UNIQUE, user_id BIGINT NOT NULL, worker_id BIGINT NOT NULL,
 score INT NOT NULL, content VARCHAR(500), created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 FOREIGN KEY(order_id) REFERENCES repair_order(id), FOREIGN KEY(user_id) REFERENCES sys_user(id), FOREIGN KEY(worker_id) REFERENCES worker_profile(id), CHECK(score BETWEEN 1 AND 5)
);
CREATE TABLE repair_complaint (
 id BIGINT PRIMARY KEY AUTO_INCREMENT, order_id BIGINT NOT NULL, user_id BIGINT NOT NULL, content VARCHAR(2000) NOT NULL,
 status VARCHAR(16) NOT NULL DEFAULT 'OPEN', handler_id BIGINT, resolution VARCHAR(1000), handled_at DATETIME(3), version INT NOT NULL DEFAULT 0,
 active_user_id BIGINT GENERATED ALWAYS AS (CASE WHEN status <> 'CLOSED' THEN user_id ELSE NULL END) STORED,
 created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3), updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 UNIQUE(order_id,active_user_id), FOREIGN KEY(order_id) REFERENCES repair_order(id), FOREIGN KEY(user_id) REFERENCES sys_user(id), FOREIGN KEY(handler_id) REFERENCES sys_user(id), CHECK(status IN ('OPEN','PROCESSING','CLOSED'))
);
CREATE TABLE repair_complaint_log (
 id BIGINT PRIMARY KEY AUTO_INCREMENT, complaint_id BIGINT NOT NULL, old_status VARCHAR(16), new_status VARCHAR(16) NOT NULL, operator_id BIGINT NOT NULL, content VARCHAR(1000) NOT NULL,
 created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3), FOREIGN KEY(complaint_id) REFERENCES repair_complaint(id), FOREIGN KEY(operator_id) REFERENCES sys_user(id)
);
CREATE TABLE notice (
 id BIGINT PRIMARY KEY AUTO_INCREMENT, community_id BIGINT NOT NULL, title VARCHAR(100) NOT NULL, content TEXT NOT NULL,
 publisher_id BIGINT NOT NULL, status VARCHAR(16) NOT NULL DEFAULT 'DRAFT', published_at DATETIME(3),
 created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3), updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 FOREIGN KEY(community_id) REFERENCES community(id), FOREIGN KEY(publisher_id) REFERENCES sys_user(id), CHECK(status IN ('DRAFT','PUBLISHED','OFFLINE'))
);
CREATE TABLE notification (
 id BIGINT PRIMARY KEY AUTO_INCREMENT, user_id BIGINT NOT NULL, order_id BIGINT, event_key VARCHAR(100) NOT NULL,
 title VARCHAR(100) NOT NULL, content VARCHAR(1000) NOT NULL, read_status TINYINT NOT NULL DEFAULT 0, read_at DATETIME(3),
 created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3), UNIQUE(user_id,event_key), INDEX(user_id,read_status,created_at),
 FOREIGN KEY(user_id) REFERENCES sys_user(id), FOREIGN KEY(order_id) REFERENCES repair_order(id)
);
CREATE TABLE sla_warning (
 id BIGINT PRIMARY KEY AUTO_INCREMENT, order_id BIGINT NOT NULL, round_no INT NOT NULL, phase VARCHAR(16) NOT NULL, level INT NOT NULL, deadline_at DATETIME(3) NOT NULL,
 created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3), UNIQUE(order_id,round_no,phase,level), FOREIGN KEY(order_id) REFERENCES repair_order(id), CHECK(level BETWEEN 1 AND 3)
);
CREATE TABLE notification_outbox (
 id BIGINT PRIMARY KEY AUTO_INCREMENT, event_key VARCHAR(100) NOT NULL, recipient_id BIGINT NOT NULL, channel VARCHAR(16) NOT NULL,
 payload JSON NOT NULL, status VARCHAR(16) NOT NULL DEFAULT 'PENDING', attempts INT NOT NULL DEFAULT 0,
 next_retry_at DATETIME(3), locked_until DATETIME(3), last_error VARCHAR(500),
 created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3), updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 UNIQUE(event_key,recipient_id,channel), INDEX(status,next_retry_at), FOREIGN KEY(recipient_id) REFERENCES sys_user(id)
);
CREATE TABLE idempotency_record (
 id BIGINT PRIMARY KEY AUTO_INCREMENT, user_id BIGINT NOT NULL, action VARCHAR(100) NOT NULL, idempotency_key VARCHAR(64) NOT NULL,
 request_hash VARCHAR(64) NOT NULL, response_json JSON, http_status INT, expires_at DATETIME(3) NOT NULL,
 created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3), UNIQUE(user_id,action,idempotency_key), FOREIGN KEY(user_id) REFERENCES sys_user(id)
);
CREATE TABLE sys_operation_log (
 id BIGINT PRIMARY KEY AUTO_INCREMENT, operator_id BIGINT, community_id BIGINT, action VARCHAR(100) NOT NULL,
 target_type VARCHAR(50) NOT NULL, target_id BIGINT, result VARCHAR(16) NOT NULL, request_id VARCHAR(64) NOT NULL,
 created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3), FOREIGN KEY(operator_id) REFERENCES sys_user(id), FOREIGN KEY(community_id) REFERENCES community(id)
);
