package com.demo.booking.saga;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.util.Map;

/** The calls the saga makes. Each one commits on its own, immediately. */
@Component
public class SagaParticipants {
    private final RestTemplate rest;
    private final String paymentUrl;
    private final String accountingUrl;

    public SagaParticipants(RestTemplate rest,
                            @Value("${demo.payment-url}") String paymentUrl,
                            @Value("${demo.accounting-url}") String accountingUrl) {
        this.rest = rest;
        this.paymentUrl = paymentUrl;
        this.accountingUrl = accountingUrl;
    }

    public void charge(String sagaId, long bookingId, int userId, BigDecimal amount) {
        post(paymentUrl + "/payments/charge", Map.of(
                "transactionId", sagaId, "bookingId", bookingId, "userId", userId, "amount", amount));
    }

    public void refund(String sagaId, long bookingId) {
        post(paymentUrl + "/payments/refund", Map.of("transactionId", sagaId, "bookingId", bookingId));
    }

    public void postRevenue(String sagaId, long bookingId, BigDecimal amount) {
        post(accountingUrl + "/ledger/revenue", Map.of(
                "transactionId", sagaId, "bookingId", bookingId, "amount", amount));
    }

    public void postReversal(String sagaId, long bookingId, BigDecimal amount) {
        post(accountingUrl + "/ledger/reversal", Map.of(
                "transactionId", sagaId, "bookingId", bookingId, "amount", amount));
    }

    private void post(String url, Map<String, Object> body) {
        rest.postForObject(url, body, Map.class);
    }
}
