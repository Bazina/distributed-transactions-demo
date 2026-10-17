package com.demo.booking.web;

import com.demo.booking.model.BookingRequest;
import com.demo.booking.saga.FailureInjection;
import com.demo.booking.saga.SagaOrchestrator;
import com.demo.booking.twopc.Coordinator;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.Map;

/** The same booking, once with two-phase commit and once with a saga. */
@RestController
public class DistributedController {
    private final Coordinator coordinator;
    private final SagaOrchestrator saga;

    public DistributedController(Coordinator coordinator, SagaOrchestrator saga) {
        this.coordinator = coordinator;
        this.saga = saga;
    }

    @PostMapping("/bookings/2pc")
    public Map<String, Object> twoPhaseCommit(@RequestBody BookingRequest request,
                                              @RequestParam(defaultValue = "120.00") BigDecimal amount) {
        return coordinator.book(request, amount);
    }

    /**
     * @param failAt fail before this step number, 0 for none
     * @param killAt stop after this step number without compensating
     */
    @PostMapping("/bookings/saga")
    public Map<String, Object> sagaBooking(@RequestBody BookingRequest request,
                                           @RequestParam(defaultValue = "120.00") BigDecimal amount,
                                           @RequestParam(defaultValue = "0") int failAt,
                                           @RequestParam(defaultValue = "0") int killAt) {
        return saga.book(request, amount, new FailureInjection(failAt, killAt));
    }

    @PostMapping("/saga/{sagaId}/resume")
    public Map<String, Object> resume(@PathVariable String sagaId) {
        return saga.resume(sagaId);
    }
}
