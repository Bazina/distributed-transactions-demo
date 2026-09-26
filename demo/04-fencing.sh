#!/usr/bin/env bash
# DDIA p.301 to 304, figure 8-5.
set -e
cd "$(dirname "$0")"; source lib.sh; ./reset.sh > /dev/null
B1=$(book 101 1 "2026-09-27 15:00:00" "2026-09-27 16:00:00")
B2=$(book 101 2 "2026-09-27 15:00:00" "2026-09-27 16:00:00")

say "client 1 takes a 3 second lease on room 101"
T1=$(curl -s -X POST "$BOOKING/locks/acquire?lockName=room:101&owner=client-1&ttlSeconds=3" \
     | sed 's/.*"token":\([0-9]*\).*/\1/')
note "fencing token $T1"

say "client 1 hits a 5 second stop-the-world pause, and does not know it"
curl -s -X POST "$BOOKING/bookings/fenced?token=$T1&pauseMs=5000" -H "$JSON" -d "$B1" > /tmp/fence-c1 &
sleep 4

say "the lease has expired, client 2 takes it"
T2=$(curl -s -X POST "$BOOKING/locks/acquire?lockName=room:101&owner=client-2&ttlSeconds=30" \
     | sed 's/.*"token":\([0-9]*\).*/\1/')
note "fencing token $T2"
printf '   client 2 writes: '; curl -s -X POST "$BOOKING/bookings/fenced?token=$T2" -H "$JSON" -d "$B2"; echo

wait
say "client 1 wakes up, still believing it holds the lock, and writes with token $T1"
printf '   '; cat /tmp/fence-c1; echo

say "what is in the database"
$MYSQL bookingdb -e "SELECT id, user_id, fence_token FROM bookings; SELECT room_id, fence_token AS high_water FROM rooms WHERE room_id=101;"
note "the rejection happened in the booking table, not in client 1."
note "Client 1 could not have checked for itself, it did not know it had been paused."
