package com.demo.booking.resources;

import com.demo.booking.model.BookingRequest;
import com.demo.booking.model.FailMode;
import com.demo.booking.services.SagaOrchestrator;
import com.demo.booking.services.TwoPhaseCommitCoordinator;
import com.demo.booking.xa.XaParticipant;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The two distributed protocols, and the switches that break them.
 */
@RestController
public class DistributedResource {

    private final TwoPhaseCommitCoordinator coordinator;
    private final SagaOrchestrator saga;
    private final XaParticipant xa;
    private final RestTemplate rest;
    private final String paymentUrl;
    private final String accountingUrl;

    public DistributedResource(TwoPhaseCommitCoordinator coordinator,
                               SagaOrchestrator saga,
                               XaParticipant xa,
                               RestTemplate rest,
                               @Value("${demo.payment-url}") String paymentUrl,
                               @Value("${demo.accounting-url}") String accountingUrl) {
        this.coordinator = coordinator;
        this.saga = saga;
        this.xa = xa;
        this.rest = rest;
        this.paymentUrl = paymentUrl;
        this.accountingUrl = accountingUrl;
    }

    @PostMapping("/bookings/2pc")
    public Map<String, Object> twoPhaseCommit(@RequestBody BookingRequest request,
                                              @RequestParam(defaultValue = "120.00") BigDecimal amount) {
        return coordinator.book(request, amount);
    }

    @PostMapping("/bookings/saga")
    public Map<String, Object> sagaBooking(@RequestBody BookingRequest request,
                                           @RequestParam(defaultValue = "120.00") BigDecimal amount,
                                           @RequestParam(defaultValue = "0") int failAt,
                                           @RequestParam(defaultValue = "0") int killAt) {
        return saga.book(request, amount, failAt, killAt);
    }

    @PostMapping("/saga/{sagaId}/resume")
    public Map<String, Object> resume(@PathVariable String sagaId) {
        return saga.resume(sagaId);
    }

    // ---- admin -----------------------------------------------------------

    @PostMapping("/admin/fail-mode")
    public Map<String, Object> failMode(@RequestParam FailMode mode) {
        coordinator.setFailMode(mode);
        return Map.of("failMode", mode);
    }

    /**
     * Every database that is currently in doubt. Asks all three, because a
     * participant cannot tell you anything about the others.
     */
    @GetMapping("/admin/in-doubt")
    public Map<String, Object> inDoubt() {
        Map<String, Object> all = new LinkedHashMap<>();
        all.put("booking", Map.of("gids", xa.inDoubt()));
        all.put("payment", ask(paymentUrl + "/xa/in-doubt"));
        all.put("accounting", ask(accountingUrl + "/xa/in-doubt"));
        return all;
    }

    /** The coordinator waking up and finishing what it started. */
    @PostMapping("/admin/recover")
    public Map<String, Object> recover() {
        return Map.of("recovered", coordinator.recover());
    }

    private Object ask(String url) {
        try {
            return rest.getForObject(url, Map.class);
        } catch (RuntimeException e) {
            return Map.of("error", "unreachable, " + e.getMessage());
        }
    }
}
