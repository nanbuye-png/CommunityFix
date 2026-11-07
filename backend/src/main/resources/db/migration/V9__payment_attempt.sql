CREATE TABLE repair_payment_attempt (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    bill_id BIGINT NOT NULL,
    attempt_no VARCHAR(64) NOT NULL UNIQUE,
    amount_fen BIGINT NOT NULL CHECK (amount_fen > 0),
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING'
        CHECK (status IN ('PENDING','EXPIRED','SUCCEEDED')),
    created_at DATETIME(3) NOT NULL,
    expires_at DATETIME(3) NOT NULL,
    pending_bill_id BIGINT GENERATED ALWAYS AS
        (CASE WHEN status='PENDING' THEN bill_id END) STORED,
    UNIQUE (pending_bill_id),
    INDEX ix_attempt_bill (bill_id, id),
    INDEX ix_attempt_expiry (status, expires_at, id),
    FOREIGN KEY (bill_id) REFERENCES repair_bill(id)
);
