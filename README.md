# Distributed Transactions Demo

A room-booking system used to demonstrate, live:

1. Write skew and phantoms on one database (DDIA p.249-251)
2. Two-phase locking and its cost (p.257-260)
3. Distributed locks and fencing tokens (p.301-304)
4. Two-phase commit, including in-doubt participants (p.353-364)
5. The saga pattern, with compensations

No business logic. Every step writes one row and one log line.

## Services

| Service | Port | Database |
|---|---|---|
| discovery-server | 8761 | - |
| booking-service | 8080 | bookingdb |
| payment-service | 8081 | paymentdb |
| accounting-service | 8082 | accountingdb |

## Running

See `demo/README.md`.
