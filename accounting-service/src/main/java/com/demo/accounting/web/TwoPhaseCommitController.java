package com.demo.accounting.web;

import com.demo.accounting.ledger.LedgerService;
import com.demo.accounting.xa.XaParticipant;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/xa")
public class TwoPhaseCommitController {
    private final XaParticipant xa;
    private final LedgerService ledger;

    public TwoPhaseCommitController(XaParticipant xa, LedgerService ledger) {
        this.xa = xa;
        this.ledger = ledger;
    }

    public record PrepareRequest(String transactionId, long bookingId, BigDecimal amount) {
    }

    public record TransactionRequest(String transactionId) {
    }

    @PostMapping("/prepare")
    public Map<String, Object> prepare(@RequestBody PrepareRequest request) {
        boolean vote = xa.prepare(request.transactionId(), connection ->
                ledger.post(connection, request.transactionId(), request.bookingId(),
                        "REVENUE", request.amount()));
        return Map.of("transactionId", request.transactionId(), "vote", vote ? "YES" : "NO");
    }

    @PostMapping("/commit")
    public Map<String, Object> commit(@RequestBody TransactionRequest request) {
        xa.commit(request.transactionId());
        return Map.of("transactionId", request.transactionId(), "state", "COMMITTED");
    }

    @PostMapping("/rollback")
    public Map<String, Object> rollback(@RequestBody TransactionRequest request) {
        xa.rollback(request.transactionId());
        return Map.of("transactionId", request.transactionId(), "state", "ABORTED");
    }

    @GetMapping("/in-doubt")
    public Map<String, Object> inDoubt() {
        List<String> ids = xa.inDoubt();
        return Map.of("service", "accounting-service", "count", ids.size(), "transactionIds", ids);
    }
}
