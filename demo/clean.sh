#!/usr/bin/env bash
# Empty every table. Schema stays, every row goes, including the seed.
# The demo scripts need the seed, so they call reset.sh, which cleans and seeds.
set -e
cd "$(dirname "$0")"; source lib.sh

# A branch left prepared still holds locks, so it has to go before the deletes.
$MYSQL -e "XA RECOVER;" | tail -n +2 | while read -r fmt glen blen data; do
  [ -n "$data" ] && $MYSQL -e "XA ROLLBACK '${data:0:$glen}','${data:$glen}';" 2>/dev/null || true
done

$MYSQL < sql/02-clean.sql
# Lives in the JVM, not in MySQL, so a script killed halfway leaves it set.
curl -s -X POST "$BOOKING/admin/fail-mode?mode=NONE" > /dev/null 2>&1 || true
echo "clean, every table is empty"
