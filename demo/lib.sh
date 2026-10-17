# Shared bits for the demo scripts.
export JAVA_HOME=${JAVA_HOME:-/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home}
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BOOKING=http://localhost:8080
PAYMENT=http://localhost:8081
ACCOUNTING=http://localhost:8082
MYSQL="mysql -u root"
JSON='Content-Type: application/json'

# The seed creates slot rows for today and tomorrow, so the demos must book
# today. Hardcoded dates go stale and make materialized lock nothing.
DAY=$(date +%F)

say ()  { printf '\n\033[1;36m== %s\033[0m\n' "$*"; }
note () { printf '\033[0;90m   %s\033[0m\n' "$*"; }
book () { printf '{"roomId":%s,"userId":%s,"startsAt":"%s","endsAt":"%s"}' "$1" "$2" "$3" "$4"; }
state () {
  $MYSQL -N -e "
    SELECT CONCAT('  booking ', id, '  room ', room_id, '  ', status) FROM bookingdb.bookings;
    SELECT CONCAT('  wallet  user ', user_id, '  ', balance) FROM paymentdb.wallets;
    SELECT CONCAT('  payment booking ', booking_id, '  ', status) FROM paymentdb.payments;
    SELECT CONCAT('  ledger  booking ', booking_id, '  ', kind, ' ', amount) FROM accountingdb.ledger;"
}

# Fire the same booking twice at once and label the answers, so it is obvious
# on a projector which request won and which one lost.
race () {
  local url=$1 body=$2
  curl -s -X POST "$url" -H "$JSON" -d "$body" > /tmp/race-A &
  curl -s -X POST "$url" -H "$JSON" -d "$body" > /tmp/race-B &
  wait
  printf '   request A: %s\n' "$(cat /tmp/race-A)"
  printf '   request B: %s\n' "$(cat /tmp/race-B)"
}
