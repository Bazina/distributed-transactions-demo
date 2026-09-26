#!/usr/bin/env bash
# DDIA figure 9-10, and p.362 on holding locks while in doubt.
set -e
cd "$(dirname "$0")"; source lib.sh; ./reset.sh > /dev/null

say "arming the coordinator to crash after collecting all the votes"
curl -s -X POST "$BOOKING/admin/fail-mode?mode=CRASH_AFTER_VOTES"; echo

say "booking room 101"
curl -s -X POST "$BOOKING/bookings/2pc?amount=120.00" -H "$JSON" \
  -d "$(book 101 1 '2026-09-27 12:00:00' '2026-09-27 13:00:00')"; echo

say "every participant is now in doubt, and none may decide alone"
curl -s "$BOOKING/admin/in-doubt"; echo

say "MySQL's own view. This is on disk, it survives restarting anything"
$MYSQL -e "XA RECOVER;"

say "nothing is visible to readers"
state

say "an unrelated write to the same wallet, with a 5 second timeout"
note "this is the part people underestimate: the locks are still held"
time $MYSQL -e "SET innodb_lock_wait_timeout=5; UPDATE paymentdb.wallets SET balance = balance WHERE user_id = 1;" || true

say "the coordinator comes back and reads its own log"
curl -s -X POST "$BOOKING/admin/recover"; echo
note "no decision was ever written, so it aborts. DDIA p.359."

say "the same write now"
time $MYSQL -e "UPDATE paymentdb.wallets SET balance = balance WHERE user_id = 1;"
curl -s -X POST "$BOOKING/admin/fail-mode?mode=NONE" > /dev/null
