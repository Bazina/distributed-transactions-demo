package com.demo.payment.resources;

import com.demo.payment.services.PaymentService;
import com.demo.payment.xa.XaParticipant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * This service as a 2PC participant. Three verbs and nothing else.
 *
 * There is no @HystrixCommand anywhere here. A two second timeout plus a retry
 * would send prepare twice, and a fallback would hide the in-doubt state that
 * the whole demo exists to show.
 */
@RestController
@RequestMapping("/xa")
public class TwoPhaseCommitResource {

    private static final Logger log = LoggerFactory.getLogger(TwoPhaseCommitResource.class);

    private final XaParticipant xa;
    private final PaymentService payments;

    public TwoPhaseCommitResource(XaParticipant xa, PaymentService payments) {
        this.xa = xa;
        this.payments = payments;
    }

    public record PrepareRequest(String gid, long bookingId, int userId, BigDecimal amount) {
    }

    public record GidRequest(String gid) {
    }

    @PostMapping("/prepare")
    public ResponseEntity<Map<String, Object>> prepare(@RequestBody PrepareRequest request) {
        log.info("[gid={}] phase 1, asked to prepare a charge of {} for user {}",
                request.gid(), request.amount(), request.userId());

        boolean vote = xa.prepare(request.gid(), connection ->
                payments.charge(connection, request.gid(), request.bookingId(),
                        request.userId(), request.amount()));

        return ResponseEntity.ok(Map.of("gid", request.gid(), "vote", vote ? "YES" : "NO"));
    }

    @PostMapping("/commit")
    public Map<String, Object> commit(@RequestBody GidRequest request) {
        log.info("[gid={}] phase 2, commit", request.gid());
        xa.commit(request.gid());
        return Map.of("gid", request.gid(), "state", "COMMITTED");
    }

    @PostMapping("/rollback")
    public Map<String, Object> rollback(@RequestBody GidRequest request) {
        log.info("[gid={}] phase 2, rollback", request.gid());
        xa.rollback(request.gid());
        return Map.of("gid", request.gid(), "state", "ABORTED");
    }

    /**
     * Everything this database is in doubt about. Survives killing this service
     * and restarting MySQL, which is the point.
     */
    @GetMapping("/in-doubt")
    public Map<String, Object> inDoubt() {
        List<String> ids = xa.inDoubt();
        return Map.of("service", "payment-service", "count", ids.size(), "gids", ids);
    }
}
