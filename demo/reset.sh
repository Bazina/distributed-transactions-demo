#!/usr/bin/env bash
# Back to a clean slate: empty every table, then seed it.
set -e
cd "$(dirname "$0")"; source lib.sh
./clean.sh > /dev/null
$MYSQL < sql/01-seed.sql > /dev/null
echo "reset done"
