package com.demo.booking.web;

import com.demo.booking.locks.LockService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/locks")
public class LockController {
    private final LockService locks;

    public LockController(LockService locks) {
        this.locks = locks;
    }

    /**
     * @return the fencing token, or 409 if somebody else holds the lease
     */
    @PostMapping("/acquire")
    public ResponseEntity<Map<String, Object>> acquire(@RequestParam String lockName,
                                                       @RequestParam String owner,
                                                       @RequestParam(defaultValue = "5") int ttlSeconds) {
        return locks.acquire(lockName, owner, ttlSeconds)
                .map(token -> ResponseEntity.ok(Map.<String, Object>of(
                        "lockName", lockName, "owner", owner, "token", token)))
                .orElseGet(() -> ResponseEntity.status(HttpStatus.CONFLICT)
                        .body(Map.of("lockName", lockName, "error", "held by someone else")));
    }

    @PostMapping("/release")
    public Map<String, Object> release(@RequestParam String lockName, @RequestParam String owner) {
        locks.release(lockName, owner);
        return Map.of("lockName", lockName, "released", true);
    }
}
