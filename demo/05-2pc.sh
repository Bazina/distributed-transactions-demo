#!/usr/bin/env bash
# DDIA p.353 to 364. Happy path, then a real no vote.
set -e
cd "$(dirname "$0")"; source lib.sh; ./reset.sh > /dev/null
curl -s -X POST "$BOOKING/admin/fail-mode?mode=NONE" > /dev/null

say "run 1, everything works. User 1 has 500.00"
curl -s -X POST "$BOOKING/bookings/2pc?amount=120.00" -H "$JSON" \
  -d "$(book 101 1 '2026-09-27 12:00:00' '2026-09-27 13:00:00')"; echo
state
note "all three rows appeared together. No reader ever saw a half state."

say "run 2, user 2 has 50.00 and the booking costs 120.00"
note "the CHECK on wallets.balance is what makes this a real no vote"
curl -s -X POST "$BOOKING/bookings/2pc?amount=120.00" -H "$JSON" \
  -d "$(book 102 2 '2026-09-27 12:00:00' '2026-09-27 13:00:00')"; echo
state
note "nothing for room 102 anywhere. One no vote aborts the whole thing."
