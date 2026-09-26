package com.demo.booking.web;

import com.demo.booking.model.FailMode;
import com.demo.booking.twopc.Coordinator;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** The switches that break two-phase commit on purpose. */
@RestController
@RequestMapping("/admin")
public class AdminController {
    private final Coordinator coordinator;

    public AdminController(Coordinator coordinator) {
        this.coordinator = coordinator;
    }

    @PostMapping("/fail-mode")
    public Map<String, Object> failMode(@RequestParam FailMode mode) {
        coordinator.setFailMode(mode);
        return Map.of("failMode", mode);
    }

    /** Asks all three databases, because a participant knows nothing about the others. */
    @GetMapping("/in-doubt")
    public Map<String, Object> inDoubt() {
        return coordinator.inDoubt();
    }

    @PostMapping("/recover")
    public Map<String, Object> recover() {
        return Map.of("recovered", coordinator.recover());
    }
}
