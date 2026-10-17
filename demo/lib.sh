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
show () {
  printf '\n   %s\n' "$1"
  $MYSQL -t -e "SELECT $2 FROM $1" | sed 's/^/   /'
}

state () {
  show bookingdb.bookings  "id, room_id, user_id, starts_at, ends_at, status, transaction_id"
  show paymentdb.wallets   "user_id, balance"
  show paymentdb.payments  "id, booking_id, user_id, amount, status, transaction_id"
  show accountingdb.ledger "id, booking_id, kind, amount, transaction_id"
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
