package com.demo.accounting.resources;

import com.demo.accounting.services.LedgerService;
import com.demo.accounting.xa.XaParticipant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * Accounting as both a 2PC participant (/xa/*) and a saga participant (/ledger/*).
 *
 * Same service, same table, two protocols, so the comparison in the lecture is
 * between the protocols and not between two different codebases.
 */
@RestController
public class AccountingResource {

    private static final Logger log = LoggerFactory.getLogger(AccountingResource.class);

    private final XaParticipant xa;
    private final LedgerService ledger;

    public AccountingResource(XaParticipant xa, LedgerService ledger) {
        this.xa = xa;
        this.ledger = ledger;
    }

    public record PrepareRequest(String gid, long bookingId, BigDecimal amount) {
    }

    public record GidRequest(String gid) {
    }

    public record PostRequest(String gid, long bookingId, BigDecimal amount) {
    }

    // ---- 2PC ---------------------------------------------------------------

    @PostMapping("/xa/prepare")
    public ResponseEntity<Map<String, Object>> prepare(@RequestBody PrepareRequest request) {
        log.info("[gid={}] phase 1, asked to prepare REVENUE {} for booking {}",
                request.gid(), request.amount(), request.bookingId());

        boolean vote = xa.prepare(request.gid(), connection ->
                ledger.post(connection, request.gid(), request.bookingId(), "REVENUE", request.amount()));

        return ResponseEntity.ok(Map.of("gid", request.gid(), "vote", vote ? "YES" : "NO"));
    }

    @PostMapping("/xa/commit")
    public Map<String, Object> commit(@RequestBody GidRequest request) {
        log.info("[gid={}] phase 2, commit", request.gid());
        xa.commit(request.gid());
        return Map.of("gid", request.gid(), "state", "COMMITTED");
    }

    @PostMapping("/xa/rollback")
    public Map<String, Object> rollback(@RequestBody GidRequest request) {
        log.info("[gid={}] phase 2, rollback", request.gid());
        xa.rollback(request.gid());
        return Map.of("gid", request.gid(), "state", "ABORTED");
    }

    @GetMapping("/xa/in-doubt")
    public Map<String, Object> inDoubt() {
        List<String> ids = xa.inDoubt();
        return Map.of("service", "accounting-service", "count", ids.size(), "gids", ids);
    }

    // ---- saga --------------------------------------------------------------

    @PostMapping("/ledger/revenue")
    public ResponseEntity<Map<String, Object>> revenue(@RequestBody PostRequest request) {
        log.info("[saga={}] step ledger REVENUE, booking {}", request.gid(), request.bookingId());
        try {
            ledger.postNow(request.gid(), request.bookingId(), "REVENUE", request.amount());
            return ResponseEntity.ok(Map.of("status", "POSTED", "kind", "REVENUE"));
        } catch (RuntimeException e) {
            log.warn("[saga={}] step ledger FAILED, {}", request.gid(), e.getMessage());
            return ResponseEntity.unprocessableEntity()
                    .body(Map.of("status", "FAILED", "reason", String.valueOf(e.getMessage())));
        }
    }

    /** The compensation. A new row, never a delete. */
    @PostMapping("/ledger/reversal")
    public Map<String, Object> reversal(@RequestBody PostRequest request) {
        log.info("[saga={}] compensation ledger REVERSAL, booking {}", request.gid(), request.bookingId());
        ledger.postNow(request.gid(), request.bookingId(), "REVERSAL", request.amount());
        return Map.of("status", "POSTED", "kind", "REVERSAL");
    }
}
