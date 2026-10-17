#!/usr/bin/env bash
# DDIA p.249. Check then insert, under REPEATABLE READ, twice at once.
set -e
cd "$(dirname "$0")"; source lib.sh; ./reset.sh > /dev/null
B=$(book 101 1 "$DAY 12:00:00" "$DAY 13:00:00")

say "two people book room 101 for 12:00 to 13:00, at the same time"
note "both check for an overlap, both see zero, both insert"
race "$BOOKING/bookings/naive?pauseMs=600" "$B"

say "the bookings table"
$MYSQL bookingdb -e "SELECT id, room_id, starts_at, ends_at, status FROM bookings;"
note "two rows. Snapshot isolation did not stop this, and no UNIQUE constraint can."
note "There was no row to lock, because the conflicting row did not exist yet. That is a phantom."
