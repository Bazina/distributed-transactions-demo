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
sagaid () { echo "$1" | sed 's/.*"sagaId":"\([^"]*\)".*/\1/'; }
txid ()   { echo "$1" | sed 's/.*"transactionId":"\([^"]*\)".*/\1/'; }
book () { printf '{"roomId":%s,"userId":%s,"startsAt":"%s","endsAt":"%s"}' "$1" "$2" "$3" "$4"; }
show () {
  printf '\n   %s\n' "$1"
  $MYSQL -t -e "SELECT $2 FROM $1 ${3:-}" | sed 's/^/   /'
}

# The final tables say what survived. These say what happened, step by step.
steps ()    { show bookingdb.saga_log "step_no, step_name, state, booking_id, amount" "WHERE saga_id = '$1' ORDER BY step_no"; }
decision () { show bookingdb.tx_log   "transaction_id, decision, updated_at" "ORDER BY updated_at"; }

# Every line any of the three services wrote for one transaction, merged in
# time order. The same panel the slides draw beside the animation, and the same
# colours: booking blue, payment orange, accounting purple. The message colour
# is the outcome, so a NO vote is readable from the back of the room.
trace () {
  printf '\n   \033[1mtrace %s\033[0m\n' "$1"
  for service in booking payment accounting; do
    case $service in
      booking)    tag=BOOKING; hue=75  ;;
      payment)    tag=PAYMENT; hue=214 ;;
      accounting) tag=ACCOUNT; hue=141 ;;
    esac
    # -a because a killed service can leave a NUL byte in its log, and grep
    # then reports "Binary file matches" instead of the lines.
    grep -ha "$1" "$ROOT/.run/$service-service.log" 2>/dev/null |
      awk -v tag="$tag" -v hue="$hue" -v id="$1" '{
        time = $1; $1 = $2 = $3 = $4 = ""; sub(/^ +/, "")
        sub("^" id " ", "")   # already in the header, once is enough
        if ($0 ~ /decision [A-Z]+ is on disk|recovering as/)                      msg = "\033[1;33m"
        else if ($0 ~ /vote NO|FAILED|ROLLBACK|failing on purpose|dies|walking away|compensat|retry/) msg = "\033[0;31m"
        else if ($0 ~ /vote YES|XA COMMIT|committed|confirmed|no-op/)             msg = "\033[0;32m"
        else                                                                      msg = "\033[0m"
        printf "\033[0;90m%s\033[0m  \033[38;5;%sm%-7s\033[0m  %s%s\033[0m\n", time, hue, tag, msg, $0
      }'
  done | sort -s -k1,1 | sed 's/^/   /'
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
