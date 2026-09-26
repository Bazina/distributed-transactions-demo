package com.demo.accounting.web;

import com.demo.accounting.ledger.LedgerService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.Map;

/** This service as a saga participant. */
@RestController
@RequestMapping("/ledger")
public class LedgerController {
    private final LedgerService ledger;

    public LedgerController(LedgerService ledger) {
        this.ledger = ledger;
    }

    public record LedgerRequest(String transactionId, long bookingId, BigDecimal amount) {
    }

    @PostMapping("/revenue")
    public ResponseEntity<Map<String, Object>> revenue(@RequestBody LedgerRequest request) {
        try {
            ledger.postNow(request.transactionId(), request.bookingId(), "REVENUE", request.amount());
            return ResponseEntity.ok(Map.of("status", "POSTED", "kind", "REVENUE"));
        } catch (RuntimeException e) {
            return ResponseEntity.unprocessableEntity()
                    .body(Map.of("status", "FAILED", "reason", String.valueOf(e.getMessage())));
        }
    }

    /** The compensation. A new row, never a delete. */
    @PostMapping("/reversal")
    public Map<String, Object> reversal(@RequestBody LedgerRequest request) {
        ledger.postNow(request.transactionId(), request.bookingId(), "REVERSAL", request.amount());
        return Map.of("status", "POSTED", "kind", "REVERSAL");
    }
}
