-- Three databases, one per service. They never talk to each other directly.
-- Run:  mysql -u root < demo/sql/00-schema.sql

DROP DATABASE IF EXISTS bookingdb;
DROP DATABASE IF EXISTS paymentdb;
DROP DATABASE IF EXISTS accountingdb;

CREATE DATABASE bookingdb;
CREATE DATABASE paymentdb;
CREATE DATABASE accountingdb;

USE bookingdb;

-- fence_token here is the resource's high-water mark, not the lock's counter.
CREATE TABLE rooms (
    room_id     INT         NOT NULL PRIMARY KEY,
    name        VARCHAR(64) NOT NULL,
    fence_token BIGINT      NOT NULL DEFAULT 0
) ENGINE = InnoDB;

-- Overlap is an inequality between two rows, so no UNIQUE key can express it.
-- That is why this table can be double booked. DDIA p.249.
CREATE TABLE bookings (
    id             BIGINT      NOT NULL AUTO_INCREMENT PRIMARY KEY,
    room_id        INT         NOT NULL,
    user_id        INT         NOT NULL,
    starts_at      DATETIME    NOT NULL,
    ends_at        DATETIME    NOT NULL,
    status         ENUM('PENDING','CONFIRMED','CANCELLED') NOT NULL,
    transaction_id VARCHAR(64) NULL,
    fence_token    BIGINT      NOT NULL DEFAULT 0,
    created_at     TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    KEY idx_room_time (room_id, starts_at, ends_at),
    CONSTRAINT fk_booking_room FOREIGN KEY (room_id) REFERENCES rooms (room_id)
) ENGINE = InnoDB;

-- Materializing conflicts, DDIA p.251. One row per room per 15 minutes.
-- Holds no booking data, it exists only so there is something to lock.
CREATE TABLE room_slot_lock (
    room_id    INT      NOT NULL,
    slot_start DATETIME NOT NULL,
    PRIMARY KEY (room_id, slot_start)
) ENGINE = InnoDB;

-- The lock service. token only increases, expiry does not reset it.
CREATE TABLE lock_lease (
    lock_name  VARCHAR(64) NOT NULL PRIMARY KEY,
    owner      VARCHAR(64) NULL,
    token      BIGINT      NOT NULL DEFAULT 0,
    expires_at DATETIME    NULL
) ENGINE = InnoDB;

-- The coordinator's durable memory. Writing here is the commit point.
CREATE TABLE tx_log (
    transaction_id VARCHAR(64) NOT NULL PRIMARY KEY,
    decision       ENUM('PREPARING','COMMIT','ABORT') NOT NULL,
    updated_at     TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP
                               ON UPDATE CURRENT_TIMESTAMP
) ENGINE = InnoDB;

-- The saga orchestrator's memory. Lets it resume after being killed.
CREATE TABLE saga_log (
    saga_id    VARCHAR(64) NOT NULL,
    step_no    INT         NOT NULL,
    step_name  VARCHAR(32) NOT NULL,
    state      ENUM('STARTED','DONE','FAILED','COMPENSATED') NOT NULL,
    booking_id BIGINT      NULL,
    amount     DECIMAL(10,2) NULL,
    at         TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (saga_id, step_no)
) ENGINE = InnoDB;

USE paymentdb;

-- The CHECK is how payment can vote no.
CREATE TABLE wallets (
    user_id INT           NOT NULL PRIMARY KEY,
    balance DECIMAL(10,2) NOT NULL,
    CONSTRAINT chk_balance_non_negative CHECK (balance >= 0)
) ENGINE = InnoDB;

-- UNIQUE(booking_id) is claimed before the money moves, so a replay is a no-op.
CREATE TABLE payments (
    id             BIGINT        NOT NULL AUTO_INCREMENT PRIMARY KEY,
    booking_id     BIGINT        NOT NULL UNIQUE,
    transaction_id VARCHAR(64)   NULL,
    user_id        INT           NOT NULL,
    amount         DECIMAL(10,2) NOT NULL,
    status         ENUM('CHARGED','REFUNDED') NOT NULL,
    created_at     TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE = InnoDB;

USE accountingdb;

-- Append only. A compensation writes a REVERSAL row, it never deletes.
CREATE TABLE ledger (
    id             BIGINT        NOT NULL AUTO_INCREMENT PRIMARY KEY,
    booking_id     BIGINT        NOT NULL,
    transaction_id VARCHAR(64)   NULL,
    kind           ENUM('REVENUE','REVERSAL') NOT NULL,
    amount         DECIMAL(10,2) NOT NULL,
    created_at     TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    KEY idx_booking (booking_id),
    UNIQUE KEY uq_booking_kind (booking_id, kind)
) ENGINE = InnoDB;
