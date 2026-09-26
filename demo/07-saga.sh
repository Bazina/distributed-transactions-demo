#!/usr/bin/env bash
# The saga, and everything 2PC does not give us.
set -e
cd "$(dirname "$0")"; source lib.sh; ./reset.sh > /dev/null

say "run 1, happy path"
note "watch the rows appear one at a time, not together"
curl -s -X POST "$BOOKING/bookings/saga?amount=120.00" -H "$JSON" \
  -d "$(book 101 1 '2026-09-27 09:00:00' '2026-09-27 10:00:00')"; echo
state

say "run 2, accounting fails at step 3"
curl -s -X POST "$BOOKING/bookings/saga?amount=120.00&failAt=3" -H "$JSON" \
  -d "$(book 102 1 '2026-09-27 09:00:00' '2026-09-27 10:00:00')"; echo
state
note "booking CANCELLED, payment REFUNDED. The refund is a new state, not a deleted row."

say "run 3, the orchestrator is killed after step 2"
R=$(curl -s -X POST "$BOOKING/bookings/saga?amount=50.00&killAt=2" -H "$JSON" \
    -d "$(book 101 1 '2026-09-27 18:00:00' '2026-09-27 19:00:00')")
echo "$R"
SID=$(echo "$R" | sed 's/.*"sagaId":"\([^"]*\)".*/\1/')
state
note "this is the middle state. The booking is PENDING and the money is already gone."
note "2PC never shows this. PENDING is the lock, other bookings treat it as taken."

say "the orchestrator restarts and reads saga_log"
curl -s -X POST "$BOOKING/saga/$SID/resume"; echo
state

say "run 4, the same charge step arrives twice"
BID=$($MYSQL -N -e "SELECT id FROM bookingdb.bookings WHERE gid='$SID';")
BAL_BEFORE=$($MYSQL -N -e "SELECT balance FROM paymentdb.wallets WHERE user_id=1;")
curl -s -X POST "$PAYMENT/payments/charge" -H "$JSON" \
  -d "{\"gid\":\"$SID\",\"bookingId\":$BID,\"userId\":1,\"amount\":50.00}"; echo
BAL_AFTER=$($MYSQL -N -e "SELECT balance FROM paymentdb.wallets WHERE user_id=1;")
note "balance before $BAL_BEFORE, after $BAL_AFTER"
note "UNIQUE(booking_id) claims the charge before the money moves, so the replay is a no-op."
