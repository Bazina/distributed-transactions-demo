-- Seed data for the demo. Inserts only, so it expects empty tables.
-- 02-clean.sql empties them, and reset.sh runs the two in order.
-- Run:  mysql -u root < demo/sql/02-clean.sql && mysql -u root < demo/sql/01-seed.sql

USE bookingdb;

INSERT INTO rooms (room_id, name, fence_token) VALUES
    (101, 'Room 101', 0),
    (102, 'Room 102', 0);

-- One lock row per room per 15 minute slot, for today and tomorrow.
-- Pre-created so a booking has existing rows to lock. This is the part the
-- book calls ugly, and it is ugly, but it is the clearest way to show the fix.
INSERT INTO room_slot_lock (room_id, slot_start)
SELECT r.room_id,
       TIMESTAMP(CURDATE()) + INTERVAL (s.n * 15) MINUTE
FROM rooms r
JOIN (
    SELECT n1.d + n2.d * 10 + n3.d * 100 AS n
    FROM (SELECT 0 d UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4
          UNION SELECT 5 UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9) n1
    CROSS JOIN (SELECT 0 d UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4
          UNION SELECT 5 UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9) n2
    CROSS JOIN (SELECT 0 d UNION SELECT 1 UNION SELECT 2) n3
) s ON s.n < 192   -- 2 days of 15 minute slots
ORDER BY r.room_id, s.n;

INSERT INTO lock_lease (lock_name, owner, token, expires_at) VALUES
    ('room:101', NULL, 0, NULL),
    ('room:102', NULL, 0, NULL);

USE paymentdb;

-- user 1 can afford a booking, user 2 cannot. user 2 is how we get a real
-- "no" vote in phase one without faking anything.
INSERT INTO wallets (user_id, balance) VALUES
    (1, 500.00),
    (2,  50.00);

USE accountingdb;

SELECT 'seeded' AS status,
       (SELECT COUNT(*) FROM bookingdb.rooms)          AS rooms,
       (SELECT COUNT(*) FROM bookingdb.room_slot_lock) AS slots,
       (SELECT COUNT(*) FROM paymentdb.wallets)        AS wallets;
