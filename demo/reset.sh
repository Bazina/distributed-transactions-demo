#!/usr/bin/env bash
# Back to a clean slate: no prepared transactions, seeded tables, no fail mode.
set -e
cd "$(dirname "$0")"; source lib.sh
$MYSQL -e "XA RECOVER;" | tail -n +2 | while read -r fmt glen blen data; do
  [ -n "$data" ] && $MYSQL -e "XA ROLLBACK '${data:0:$glen}','${data:$glen}';" 2>/dev/null || true
done
$MYSQL < sql/01-seed.sql > /dev/null
# Lives in the JVM, not in MySQL, so a script killed halfway leaves it set.
curl -s -X POST "$BOOKING/admin/fail-mode?mode=NONE" > /dev/null 2>&1 || true
echo "reset done"
