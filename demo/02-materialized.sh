#!/usr/bin/env bash
# DDIA p.251. Give the phantom something concrete to lock.
set -e
cd "$(dirname "$0")"; source lib.sh; ./reset.sh > /dev/null
B=$(book 101 1 "$DAY 12:00:00" "$DAY 13:00:00")

say "same race, but each request locks the 15 minute slot rows first"
note "room_slot_lock holds no booking data. It exists only to be locked."
race "$BOOKING/bookings/materialized?pauseMs=600" "$B"

say "the bookings table"
$MYSQL bookingdb -e "SELECT id, room_id, status FROM bookings;"
note "one row. The second request waited, then found the first booking and gave up."
