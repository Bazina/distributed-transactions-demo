-- Empties every table. Schema is untouched, only rows go.
-- Children before parents, because bookings references rooms.
-- Run:  mysql -u root < demo/sql/02-clean.sql

DELETE FROM bookingdb.bookings;
DELETE FROM bookingdb.room_slot_lock;
DELETE FROM bookingdb.lock_lease;
DELETE FROM bookingdb.tx_log;
DELETE FROM bookingdb.saga_log;
DELETE FROM bookingdb.rooms;

DELETE FROM paymentdb.payments;
DELETE FROM paymentdb.wallets;

DELETE FROM accountingdb.ledger;

-- Ids restart at 1 every run, so the tables stay readable on a projector.
ALTER TABLE bookingdb.bookings  AUTO_INCREMENT = 1;
ALTER TABLE paymentdb.payments  AUTO_INCREMENT = 1;
ALTER TABLE accountingdb.ledger AUTO_INCREMENT = 1;
