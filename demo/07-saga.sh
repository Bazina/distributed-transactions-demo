#!/usr/bin/env bash
# The saga, and everything 2PC does not give us.
set -e
cd "$(dirname "$0")"; source lib.sh; ./reset.sh > /dev/null

say "run 1, happy path"
note "four steps: 1 reserve, 2 charge, 3 ledger, 4 confirm"
R=$(curl -s -X POST "$BOOKING/bookings/saga?amount=120.00" -H "$JSON" \
    -d "$(book 101 1 "$DAY 09:00:00" "$DAY 10:00:00")")
echo "$R"
SID=$(sagaid "$R")
trace "$SID"
steps "$SID"
note "each step committed on its own. The rows appeared one at a time, not together."
state

say "run 2, accounting fails at step 3"
R=$(curl -s -X POST "$BOOKING/bookings/saga?amount=120.00&failAt=3" -H "$JSON" \
    -d "$(book 102 1 "$DAY 09:00:00" "$DAY 10:00:00")")
echo "$R"
SID=$(sagaid "$R")
trace "$SID"
steps "$SID"
note "3 ledger FAILED, then 2 charge and 1 reserve COMPENSATED, in reverse"
state
note "booking CANCELLED, payment REFUNDED. The refund is a new state, not a deleted row."

say "run 3, the orchestrator is killed after step 2"
R=$(curl -s -X POST "$BOOKING/bookings/saga?amount=50.00&killAt=2" -H "$JSON" \
    -d "$(book 101 1 "$DAY 18:00:00" "$DAY 19:00:00")")
echo "$R"
SID=$(sagaid "$R")
trace "$SID"
steps "$SID"
note "the log stops at step 2. Steps 3 and 4 never ran and nothing was compensated."
state
note "this is the middle state. The booking is PENDING and the money is already gone."
note "2PC never shows this. PENDING is the lock, other bookings treat it as taken."

say "the orchestrator restarts and reads saga_log"
curl -s -X POST "$BOOKING/saga/$SID/resume"; echo
trace "$SID"
steps "$SID"
note "last DONE was step 2, so it carried on from step 3."
state

say "run 4, the same charge step arrives twice"
BID=$($MYSQL -N -e "SELECT id FROM bookingdb.bookings WHERE transaction_id='$SID';")
BAL_BEFORE=$($MYSQL -N -e "SELECT balance FROM paymentdb.wallets WHERE user_id=1;")
curl -s -X POST "$PAYMENT/payments/charge" -H "$JSON" \
  -d "{\"transactionId\":\"$SID\",\"bookingId\":$BID,\"userId\":1,\"amount\":50.00}"; echo
BAL_AFTER=$($MYSQL -N -e "SELECT balance FROM paymentdb.wallets WHERE user_id=1;")
note "balance before $BAL_BEFORE, after $BAL_AFTER"
note "UNIQUE(booking_id) claims the charge before the money moves, so the replay is a no-op."
