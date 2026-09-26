package com.demo.payment.web;

import com.demo.payment.payments.PaymentService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.Map;

/**
 * This service as a saga participant. charge commits straight away, so if a
 * later step fails somebody has to call refund. Nothing rolls back on its own.
 */
@RestController
@RequestMapping("/payments")
public class PaymentController {
    private final PaymentService payments;

    public PaymentController(PaymentService payments) {
        this.payments = payments;
    }

    public record ChargeRequest(String transactionId, long bookingId, int userId, BigDecimal amount) {
    }

    public record RefundRequest(String transactionId, long bookingId) {
    }

    @PostMapping("/charge")
    public ResponseEntity<Map<String, Object>> charge(@RequestBody ChargeRequest request) {
        try {
            payments.chargeNow(request.transactionId(), request.bookingId(),
                    request.userId(), request.amount());
            return ResponseEntity.ok(Map.of("status", "CHARGED", "bookingId", request.bookingId()));
        } catch (RuntimeException e) {
            return ResponseEntity.unprocessableEntity()
                    .body(Map.of("status", "FAILED", "reason", String.valueOf(e.getMessage())));
        }
    }

    @PostMapping("/refund")
    public Map<String, Object> refund(@RequestBody RefundRequest request) {
        payments.refund(request.transactionId(), request.bookingId());
        return Map.of("status", "REFUNDED", "bookingId", request.bookingId());
    }
}
