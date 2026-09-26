package com.demo.booking.resources;

import com.demo.booking.model.BookingRequest;
import com.demo.booking.services.BookingService;
import com.demo.booking.services.LockService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * The single database part of the demo. Four ways to book the same room.
 *
 * Every endpoint takes the same body, so the only thing that changes between
 * them is the isolation strategy.
 */
@RestController
@RequestMapping("/bookings")
public class BookingResource {

    private final BookingService bookings;
    private final LockService locks;

    public BookingResource(BookingService bookings, LockService locks) {
        this.bookings = bookings;
        this.locks = locks;
    }

    /** Broken. Two of these at once double-book the room. DDIA p.249. */
    @PostMapping("/naive")
    public ResponseEntity<Map<String, Object>> naive(@RequestBody BookingRequest request,
                                                     @RequestParam(defaultValue = "0") long pauseMs) {
        return run(() -> bookings.bookNaive(request, pauseMs), "naive");
    }

    /** Fixed by giving the phantom something concrete to lock. DDIA p.251. */
    @PostMapping("/materialized")
    public ResponseEntity<Map<String, Object>> materialized(@RequestBody BookingRequest request,
                                                            @RequestParam(defaultValue = "0") long pauseMs) {
        return run(() -> bookings.bookMaterialized(request, pauseMs), "materialized");
    }

    /** Fixed by the database, at the price of aborts and retries. DDIA p.257. */
    @PostMapping("/serializable")
    public ResponseEntity<Map<String, Object>> serializable(@RequestBody BookingRequest request,
                                                            @RequestParam(defaultValue = "0") long pauseMs,
                                                            @RequestParam(defaultValue = "3") int attempts) {
        return run(() -> bookings.bookSerializable(request, pauseMs, attempts), "serializable");
    }

    /**
     * Write carrying a fencing token. The token is checked by this service,
     * against the rooms row, in the same transaction as the insert.
     */
    @PostMapping("/fenced")
    public ResponseEntity<Map<String, Object>> fenced(@RequestBody BookingRequest request,
                                                      @RequestParam long token,
                                                      @RequestParam(defaultValue = "0") long pauseMs) {
        if (pauseMs > 0) {
            // The fake stop-the-world pause. The lease expires while we sleep
            // and we have no idea. DDIA figure 8-4.
            try {
                Thread.sleep(pauseMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        return run(() -> bookings.bookFenced(request, token), "fenced");
    }

    // ---- the lock service ------------------------------------------------

    @PostMapping("/locks/acquire")
    public ResponseEntity<Map<String, Object>> acquire(@RequestParam String lockName,
                                                       @RequestParam String owner,
                                                       @RequestParam(defaultValue = "5") int ttlSeconds) {
        return locks.acquire(lockName, owner, ttlSeconds)
                .map(token -> ResponseEntity.ok(Map.<String, Object>of(
                        "lockName", lockName, "owner", owner, "token", token)))
                .orElseGet(() -> ResponseEntity.status(HttpStatus.CONFLICT)
                        .body(Map.of("lockName", lockName, "error", "held by someone else")));
    }

    @PostMapping("/locks/release")
    public Map<String, Object> release(@RequestParam String lockName, @RequestParam String owner) {
        locks.release(lockName, owner);
        return Map.of("lockName", lockName, "released", true);
    }

    private ResponseEntity<Map<String, Object>> run(BookingAttempt attempt, String strategy) {
        try {
            long id = attempt.get();
            return ResponseEntity.ok(Map.of("strategy", strategy, "bookingId", id, "status", "CONFIRMED"));
        } catch (RuntimeException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("strategy", strategy, "status", "REJECTED",
                            "reason", String.valueOf(e.getMessage())));
        }
    }

    @FunctionalInterface
    private interface BookingAttempt {
        long get();
    }
}
