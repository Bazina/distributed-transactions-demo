package com.demo.payment.resources;

import com.demo.payment.services.PaymentService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.Map;

/**
 * This service as a saga participant.
 *
 * The difference from the 2PC side is the whole lesson: charge commits straight
 * away, so the money is really gone the moment this returns. If a later step
 * fails, someone has to call refund. Nothing rolls back on its own.
 */
@RestController
@RequestMapping("/payments")
public class SagaResource {

    private static final Logger log = LoggerFactory.getLogger(SagaResource.class);

    private final PaymentService payments;

    public SagaResource(PaymentService payments) {
        this.payments = payments;
    }

    public record ChargeRequest(String gid, long bookingId, int userId, BigDecimal amount) {
    }

    public record RefundRequest(String gid, long bookingId) {
    }

    @PostMapping("/charge")
    public ResponseEntity<Map<String, Object>> charge(@RequestBody ChargeRequest request) {
        log.info("[saga={}] step charge, booking {}", request.gid(), request.bookingId());
        try {
            payments.chargeNow(request.gid(), request.bookingId(), request.userId(), request.amount());
            return ResponseEntity.ok(Map.of("status", "CHARGED", "bookingId", request.bookingId()));
        } catch (RuntimeException e) {
            log.warn("[saga={}] step charge FAILED, {}", request.gid(), e.getMessage());
            return ResponseEntity.unprocessableEntity()
                    .body(Map.of("status", "FAILED", "reason", String.valueOf(e.getMessage())));
        }
    }

    @PostMapping("/refund")
    public Map<String, Object> refund(@RequestBody RefundRequest request) {
        log.info("[saga={}] compensation refund, booking {}", request.gid(), request.bookingId());
        payments.refund(request.gid(), request.bookingId());
        return Map.of("status", "REFUNDED", "bookingId", request.bookingId());
    }
}
