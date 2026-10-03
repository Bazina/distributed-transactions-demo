#!/usr/bin/env bash
# DDIA p.257 to 260. Let the database do it, and pay for it.
set -e
cd "$(dirname "$0")"; source lib.sh; ./reset.sh > /dev/null
B=$(book 101 1 "$DAY 12:00:00" "$DAY 13:00:00")

say "same race at SERIALIZABLE, no helper table"
note "InnoDB turns the reads into locking reads and uses next-key locks on the gap"
curl -s -X POST "$BOOKING/bookings/serializable?pauseMs=600" -H "$JSON" -d "$B" &
curl -s -X POST "$BOOKING/bookings/serializable?pauseMs=600" -H "$JSON" -d "$B" &
wait; echo

say "the bookings table"
$MYSQL bookingdb -e "SELECT id, room_id, status FROM bookings;"
note "one row again. Look in .run/booking-service.log for an aborted attempt and a retry."
note "That retry is the cost of 2PL, and it is why 2PL is slow under contention."
