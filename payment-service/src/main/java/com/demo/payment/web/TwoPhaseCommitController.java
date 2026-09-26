package com.demo.payment.web;

import com.demo.payment.payments.PaymentService;
import com.demo.payment.xa.XaParticipant;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/** This service as a participant. Three verbs, no Hystrix around any of them. */
@RestController
@RequestMapping("/xa")
public class TwoPhaseCommitController {
    private final XaParticipant xa;
    private final PaymentService payments;

    public TwoPhaseCommitController(XaParticipant xa, PaymentService payments) {
        this.xa = xa;
        this.payments = payments;
    }

    public record PrepareRequest(String transactionId, long bookingId, int userId, BigDecimal amount) {
    }

    public record TransactionRequest(String transactionId) {
    }

    /**
     * @return the vote, YES or NO
     */
    @PostMapping("/prepare")
    public Map<String, Object> prepare(@RequestBody PrepareRequest request) {
        boolean vote = xa.prepare(request.transactionId(), connection ->
                payments.charge(connection, request.transactionId(), request.bookingId(),
                        request.userId(), request.amount()));
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

    /** Survives killing this service and restarting MySQL, which is the point. */
    @GetMapping("/in-doubt")
    public Map<String, Object> inDoubt() {
        List<String> ids = xa.inDoubt();
        return Map.of("service", "payment-service", "count", ids.size(), "transactionIds", ids);
    }
}
