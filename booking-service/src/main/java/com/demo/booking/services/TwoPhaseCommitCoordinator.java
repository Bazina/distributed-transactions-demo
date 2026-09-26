package com.demo.booking.services;

import com.demo.booking.model.BookingRequest;
import com.demo.booking.model.FailMode;
import com.demo.booking.xa.XaParticipant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The coordinator, also known as the transaction manager. DDIA p.356.
 *
 * It is a participant too: booking-service owns bookingdb, so it prepares its
 * own branch alongside the other two.
 *
 * The one thing worth staring at is the order of operations in book(). The
 * write to tx_log is the commit point. Before it, anyone may abort. After it,
 * the decision is final and phase two is retried until it lands, no matter how
 * many participants have to come back from the dead first.
 */
@Service
public class TwoPhaseCommitCoordinator {

    private static final Logger log = LoggerFactory.getLogger(TwoPhaseCommitCoordinator.class);

    private final DataSource dataSource;
    private final XaParticipant xa;
    private final BookingService bookings;
    private final RestTemplate rest;
    private final String paymentUrl;
    private final String accountingUrl;

    private volatile FailMode failMode = FailMode.NONE;

    public TwoPhaseCommitCoordinator(DataSource dataSource,
                                     XaParticipant xa,
                                     BookingService bookings,
                                     RestTemplate rest,
                                     @Value("${demo.payment-url}") String paymentUrl,
                                     @Value("${demo.accounting-url}") String accountingUrl) {
        this.dataSource = dataSource;
        this.xa = xa;
        this.bookings = bookings;
        this.rest = rest;
        this.paymentUrl = paymentUrl;
        this.accountingUrl = accountingUrl;
    }

    public void setFailMode(FailMode mode) {
        this.failMode = mode;
        log.warn("fail mode is now {}", mode);
    }

    public FailMode getFailMode() {
        return failMode;
    }

    public Map<String, Object> book(BookingRequest request, BigDecimal amount) {
        String gid = "bk-" + UUID.randomUUID().toString().substring(0, 8);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("gid", gid);

        // The booking id has to exist before payment and accounting can refer to
        // it, and it has to be inside our own branch so it disappears on abort.
        long bookingId = nextBookingId();
        result.put("bookingId", bookingId);

        log.info("[gid={}] begin, room {} for user {}, amount {}",
                gid, request.roomId(), request.userId(), amount);
        writeTxLog(gid, "PREPARING");

        // ---- phase one ------------------------------------------------------
        boolean bookingVote = xa.prepare(gid, connection -> {
            if (bookings.countOverlapping(connection, request) > 0) {
                throw new SQLException("room already booked");
            }
            bookings.insertWithId(connection, bookingId, request, "CONFIRMED", gid);
        });
        log.info("[gid={}] booking voted {}", gid, bookingVote ? "YES" : "NO");

        boolean paymentVote = bookingVote && vote(paymentUrl + "/xa/prepare", Map.of(
                "gid", gid, "bookingId", bookingId,
                "userId", request.userId(), "amount", amount));
        log.info("[gid={}] payment voted {}", gid, paymentVote ? "YES" : "NO");

        boolean accountingVote = paymentVote && vote(accountingUrl + "/xa/prepare", Map.of(
                "gid", gid, "bookingId", bookingId, "amount", amount));
        log.info("[gid={}] accounting voted {}", gid, accountingVote ? "YES" : "NO");

        boolean allYes = bookingVote && paymentVote && accountingVote;

        // ---- the coordinator crashes here, on purpose -----------------------
        if (allYes && failMode == FailMode.CRASH_AFTER_VOTES) {
            log.error("[gid={}] everyone voted yes and I am walking away. " +
                    "Three databases are now in doubt and holding locks.", gid);
            result.put("state", "COORDINATOR_CRASHED");
            result.put("hint", "GET /admin/in-doubt, then POST /admin/recover");
            return result;
        }

        // ---- the commit point ----------------------------------------------
        String decision = allYes ? "COMMIT" : "ABORT";
        writeTxLog(gid, decision);
        log.info("[gid={}] decision {} is on disk, there is no going back now", gid, decision);

        // ---- phase two ------------------------------------------------------
        applyDecision(gid, decision, bookingVote, paymentVote, accountingVote);

        result.put("state", "COMMIT".equals(decision) ? "COMMITTED" : "ABORTED");
        return result;
    }

    /**
     * Phase two. Every one of these is safe to call twice, so a retry after a
     * crash costs nothing.
     */
    private void applyDecision(String gid, String decision,
                               boolean bookingPrepared, boolean paymentPrepared, boolean accountingPrepared) {
        boolean commit = "COMMIT".equals(decision);

        if (bookingPrepared) {
            if (commit) {
                xa.commit(gid);
            } else {
                xa.rollback(gid);
            }
        }
        if (paymentPrepared) {
            if (failMode == FailMode.COMMIT_LOST_TO_PAYMENT && commit) {
                log.error("[gid={}] phase 2 message to payment is lost. " +
                        "The decision still stands, it just has not landed yet.", gid);
            } else {
                call(paymentUrl + "/xa/" + (commit ? "commit" : "rollback"), Map.of("gid", gid));
            }
        }
        if (accountingPrepared) {
            call(accountingUrl + "/xa/" + (commit ? "commit" : "rollback"), Map.of("gid", gid));
        }
    }

    /**
     * What a coordinator does when it wakes up. Reads its own log and finishes
     * anything it had decided, aborts anything it had not. DDIA p.359: any
     * transaction with no commit record is aborted.
     */
    public Map<String, Object> recover() {
        Map<String, Object> summary = new LinkedHashMap<>();
        List<Map<String, String>> pending = readTxLog();

        for (Map<String, String> row : pending) {
            String gid = row.get("gid");
            String decision = row.get("decision");

            if ("PREPARING".equals(decision)) {
                // We never reached the commit point, so nobody can have committed.
                log.warn("[gid={}] no decision was ever written, aborting", gid);
                writeTxLog(gid, "ABORT");
                decision = "ABORT";
            } else {
                log.info("[gid={}] log says {}, finishing what was started", gid, decision);
            }
            applyDecision(gid, decision, true, true, true);
            summary.put(gid, decision);
        }
        if (summary.isEmpty()) {
            log.info("nothing to recover");
        }
        return summary;
    }

    // ------------------------------------------------------------------

    private boolean vote(String url, Map<String, Object> body) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> response = rest.postForObject(url, body, Map.class);
            return response != null && "YES".equals(response.get("vote"));
        } catch (RuntimeException e) {
            // A prepare that times out or fails is a no. DDIA p.357.
            log.warn("prepare to {} failed, counting it as NO: {}", url, e.getMessage());
            return false;
        }
    }

    private void call(String url, Map<String, Object> body) {
        try {
            rest.postForObject(url, body, Map.class);
        } catch (RuntimeException e) {
            log.error("phase 2 call to {} failed, it has to be retried: {}", url, e.getMessage());
        }
    }

    private long nextBookingId() {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement next = connection.prepareStatement(
                     "SELECT COALESCE(MAX(id), 0) + 1 FROM bookings");
             ResultSet row = next.executeQuery()) {
            row.next();
            return row.getLong(1);
        } catch (SQLException e) {
            throw new IllegalStateException("could not allocate a booking id", e);
        }
    }

    private void writeTxLog(String gid, String decision) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement write = connection.prepareStatement(
                     "INSERT INTO tx_log (gid, decision, participants) VALUES (?, ?, 'booking,payment,accounting') " +
                     "ON DUPLICATE KEY UPDATE decision = VALUES(decision)")) {
            write.setString(1, gid);
            write.setString(2, decision);
            write.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("could not write tx_log for " + gid, e);
        }
    }

    private List<Map<String, String>> readTxLog() {
        List<Map<String, String>> rows = new java.util.ArrayList<>();
        try (Connection connection = dataSource.getConnection();
             PreparedStatement read = connection.prepareStatement(
                     "SELECT gid, decision FROM tx_log WHERE decision <> 'ABORT' ORDER BY updated_at");
             ResultSet result = read.executeQuery()) {
            while (result.next()) {
                rows.add(Map.of("gid", result.getString("gid"), "decision", result.getString("decision")));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("could not read tx_log", e);
        }
        return rows;
    }
}
