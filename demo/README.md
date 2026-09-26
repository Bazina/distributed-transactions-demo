# Running the demo

## Once

```bash
mysql -u root < demo/sql/00-schema.sql    # creates the three databases
demo/00-start.sh                          # builds if needed, starts all four services
```

Services log to `.run/*.log`. Stop everything with `demo/99-stop.sh`.

Requires a JDK 17. If yours is not at the Homebrew path, set `JAVA_HOME` first.

## The scripts, in lecture order

Each one resets the databases first, so they can be run in any order and repeated.

| Script | Shows | DDIA |
|---|---|---|
| `01-write-skew.sh` | two concurrent bookings, both succeed, room 101 booked twice | p.249 |
| `02-materialized.sh` | the same race, fixed by locking pre-created slot rows | p.251 |
| `03-serializable.sh` | the same race, fixed by the database, at the cost of a retry | p.257 |
| `04-fencing.sh` | a paused client writes with a stale token and is rejected | p.301 |
| `05-2pc.sh` | 2PC happy path, then a real "no" vote from a CHECK constraint | p.353 |
| `06-2pc-in-doubt.sh` | coordinator crashes after the votes, locks stay held, recovery | p.357 |
| `07-saga.sh` | happy, compensation, orchestrator crash and resume, replayed step | - |

`06` is the one to spend time on. It ends with an unrelated write blocking for
five seconds and timing out, then completing in milliseconds once the
coordinator recovers.

## Watching it happen

Two terminals side by side works well:

```bash
tail -f .run/booking-service.log       # the coordinator or orchestrator talking
watch -n1 'mysql -u root -e "XA RECOVER"'   # what MySQL is in doubt about
```

Every log line carries the global transaction id, so `grep bk-xxxxxxxx .run/*.log`
gives the whole story of one transaction across all three services.

## Poking at it by hand

```bash
# one booking, two protocols
curl -X POST 'localhost:8080/bookings/2pc?amount=120.00'  -H 'Content-Type: application/json' \
     -d '{"roomId":101,"userId":1,"startsAt":"2026-09-27 12:00:00","endsAt":"2026-09-27 13:00:00"}'

curl -X POST 'localhost:8080/bookings/saga?amount=120.00&failAt=3' -H 'Content-Type: application/json' \
     -d '{"roomId":101,"userId":1,"startsAt":"2026-09-27 12:00:00","endsAt":"2026-09-27 13:00:00"}'

# break 2PC on purpose
curl -X POST 'localhost:8080/admin/fail-mode?mode=CRASH_AFTER_VOTES'
curl -X POST 'localhost:8080/admin/fail-mode?mode=COMMIT_LOST_TO_PAYMENT'
curl -X POST 'localhost:8080/admin/fail-mode?mode=NONE'

curl localhost:8080/admin/in-doubt
curl -X POST localhost:8080/admin/recover
```

## Two things worth knowing

**All three databases are on one MySQL server.** XA transaction ids are global to
the server, not to a schema, so each participant supplies its own branch
qualifier: the XID is `(gtrid, bqual)`, one global transaction with one branch
per service. Without that, the second participant to prepare gets `XAER_DUPID`.
On three separate servers this would not come up.

**`XaParticipant` is copy-pasted into all three services.** There is no shared
module on purpose, so each service can be read on its own in a lecture.
