#!/usr/bin/env bash
# Back to a clean slate. Also rolls back any prepared transaction left behind.
set -e
cd "$(dirname "$0")"; source lib.sh
$MYSQL -e "XA RECOVER;" | tail -n +2 | while read -r fmt glen blen data; do
  [ -n "$data" ] && $MYSQL -e "XA ROLLBACK '${data:0:$glen}','${data:$glen}';" 2>/dev/null || true
done
$MYSQL < sql/01-seed.sql > /dev/null
echo "reset done"
