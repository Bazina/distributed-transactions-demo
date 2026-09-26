package com.demo.booking.web;

import com.demo.booking.bookings.BookingStrategies;
import com.demo.booking.model.BookingRequest;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** The single database part of the demo. Same body, four isolation strategies. */
@RestController
@RequestMapping("/bookings")
public class BookingController {
    private final BookingStrategies strategies;

    public BookingController(BookingStrategies strategies) {
        this.strategies = strategies;
    }

    /**
     * @param pauseMs widens the race window so the second request can lose on purpose
     */
    @PostMapping("/naive")
    public Map<String, Object> naive(@RequestBody BookingRequest request,
                                     @RequestParam(defaultValue = "0") long pauseMs) {
        return confirmed("naive", strategies.naive(request, pauseMs));
    }

    @PostMapping("/materialized")
    public Map<String, Object> materialized(@RequestBody BookingRequest request,
                                            @RequestParam(defaultValue = "0") long pauseMs) {
        return confirmed("materialized", strategies.materialized(request, pauseMs));
    }

    @PostMapping("/serializable")
    public Map<String, Object> serializable(@RequestBody BookingRequest request,
                                            @RequestParam(defaultValue = "0") long pauseMs,
                                            @RequestParam(defaultValue = "3") int attempts) {
        return confirmed("serializable", strategies.serializable(request, pauseMs, attempts));
    }

    /**
     * @param token   the fencing token this client believes is current
     * @param pauseMs a fake stop-the-world pause before the write, so the lease expires
     */
    @PostMapping("/fenced")
    public Map<String, Object> fenced(@RequestBody BookingRequest request,
                                      @RequestParam long token,
                                      @RequestParam(defaultValue = "0") long pauseMs) {
        sleep(pauseMs);
        return confirmed("fenced", strategies.fenced(request, token));
    }

    private Map<String, Object> confirmed(String strategy, long bookingId) {
        return Map.of("strategy", strategy, "bookingId", bookingId, "status", "CONFIRMED");
    }

    private static void sleep(long millis) {
        if (millis <= 0) {
            return;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
