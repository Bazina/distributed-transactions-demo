package com.demo.booking.services;

import com.demo.booking.model.BookingRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * The saga. Four steps, each one committed on its own the moment it runs.
 *
 * Nothing here is atomic and nothing here is isolated. Between step 1 and step 4
 * a reader sees a PENDING booking, and possibly a REVENUE row that is about to
 * be reversed. 2PC never shows that middle state; this is what we trade for not
 * holding locks across services.
 *
 * PENDING is the lock. Other bookings treat a PENDING row as taken.
 */
@Service
public class SagaOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(SagaOrchestrator.class);

    private final DataSource dataSource;
    private final BookingService bookings;
    private final RestTemplate rest;
    private final String paymentUrl;
    private final String accountingUrl;

    public SagaOrchestrator(DataSource dataSource,
                            BookingService bookings,
                            RestTemplate rest,
                            @Value("${demo.payment-url}") String paymentUrl,
                            @Value("${demo.accounting-url}") String accountingUrl) {
        this.dataSource = dataSource;
        this.bookings = bookings;
        this.rest = rest;
        this.paymentUrl = paymentUrl;
        this.accountingUrl = accountingUrl;
    }

    /** One step and the thing that undoes it. */
    private record Step(String name, Runnable action, Runnable compensation) {
    }

    /**
     * @param failAt  fail on purpose before running this step number, 0 for none
     * @param killAt  stop dead after this step number, simulating the
     *                orchestrator being killed mid saga. Nothing is compensated,
     *                the saga is left half done for resume() to pick up.
     */
    public Map<String, Object> book(BookingRequest request, BigDecimal amount, int failAt, int killAt) {
        String sagaId = "sg-" + UUID.randomUUID().toString().substring(0, 8);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("sagaId", sagaId);

        long bookingId = reserve(sagaId, request);
        result.put("bookingId", bookingId);

        Deque<Step> done = new ArrayDeque<>();
        done.push(new Step("reserve",
                () -> {
                },
                () -> bookings.setStatus(bookingId, "CANCELLED")));

        var steps = new Step[]{
                new Step("charge",
                        () -> post(paymentUrl + "/payments/charge", Map.of(
                                "gid", sagaId, "bookingId", bookingId,
                                "userId", request.userId(), "amount", amount)),
                        () -> post(paymentUrl + "/payments/refund", Map.of(
                                "gid", sagaId, "bookingId", bookingId))),

                new Step("ledger",
                        () -> post(accountingUrl + "/ledger/revenue", Map.of(
                                "gid", sagaId, "bookingId", bookingId, "amount", amount)),
                        () -> post(accountingUrl + "/ledger/reversal", Map.of(
                                "gid", sagaId, "bookingId", bookingId, "amount", amount))),

                new Step("confirm",
                        () -> bookings.setStatus(bookingId, "CONFIRMED"),
                        () -> {
                            // The last step has nothing after it to undo.
                        })
        };

        for (int i = 0; i < steps.length; i++) {
            int stepNo = i + 2; // reserve was step 1
            Step step = steps[i];

            if (failAt == stepNo) {
                log.error("[saga={}] step {} {} failing on purpose", sagaId, stepNo, step.name());
                writeLog(sagaId, stepNo, step.name(), "FAILED", "injected");
                compensate(sagaId, done);
                result.put("state", "COMPENSATED");
                return result;
            }

            writeLog(sagaId, stepNo, step.name(), "STARTED", null);
            try {
                step.action().run();
            } catch (RuntimeException e) {
                log.error("[saga={}] step {} {} failed, {}", sagaId, stepNo, step.name(), e.getMessage());
                writeLog(sagaId, stepNo, step.name(), "FAILED", e.getMessage());
                compensate(sagaId, done);
                result.put("state", "COMPENSATED");
                result.put("reason", String.valueOf(e.getMessage()));
                return result;
            }
            writeLog(sagaId, stepNo, step.name(), "DONE", null);
            done.push(step);

            if (killAt == stepNo) {
                log.error("[saga={}] orchestrator dies here. Booking is still PENDING and the money " +
                        "is already gone. Nothing is compensated. POST /saga/{}/resume to continue.",
                        sagaId, sagaId);
                result.put("state", "ABANDONED");
                return result;
            }
        }

        result.put("state", "CONFIRMED");
        return result;
    }

    /**
     * What the orchestrator does when it comes back up. Reads saga_log, sees
     * which step was last DONE, and carries on from there.
     */
    public Map<String, Object> resume(String sagaId) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("sagaId", sagaId);

        Map<String, String> state = lastStep(sagaId);
        if (state.isEmpty()) {
            result.put("state", "UNKNOWN_SAGA");
            return result;
        }

        int lastDone = Integer.parseInt(state.get("step_no"));
        long bookingId = Long.parseLong(state.get("booking_id"));
        log.info("[saga={}] resuming, last completed step was {} {}", sagaId, lastDone, state.get("step_name"));

        if (lastDone < 3) {
            post(accountingUrl + "/ledger/revenue", Map.of(
                    "gid", sagaId, "bookingId", bookingId, "amount", state.get("amount")));
            writeLog(sagaId, 3, "ledger", "DONE", "resumed");
        }
        if (lastDone < 4) {
            bookings.setStatus(bookingId, "CONFIRMED");
            writeLog(sagaId, 4, "confirm", "DONE", "resumed");
        }
        result.put("state", "CONFIRMED");
        return result;
    }

    /** Backwards, in reverse order, exactly the steps that actually ran. */
    private void compensate(String sagaId, Deque<Step> done) {
        log.warn("[saga={}] compensating {} completed steps, in reverse", sagaId, done.size());
        int stepNo = done.size() + 1;
        while (!done.isEmpty()) {
            Step step = done.pop();
            try {
                step.compensation().run();
                writeLog(sagaId, 100 + stepNo, step.name(), "COMPENSATED", null);
            } catch (RuntimeException e) {
                // A compensation that fails has to be retried. It cannot be
                // skipped, there is nothing else that will clean this up.
                log.error("[saga={}] compensation for {} FAILED, {}. This needs a retry.",
                        sagaId, step.name(), e.getMessage());
            }
            stepNo--;
        }
    }

    private long reserve(String sagaId, BookingRequest request) {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                bookings.lockSlots(connection, request);
                if (bookings.countOverlapping(connection, request) > 0) {
                    connection.rollback();
                    throw new IllegalStateException("room already booked");
                }
                long id = bookings.insert(connection, request, "PENDING", sagaId, 0);
                connection.commit();
                log.info("[saga={}] step 1 reserve, booking {} is PENDING", sagaId, id);
                writeLog(sagaId, 1, "reserve", "DONE", String.valueOf(id));
                return id;
            } catch (SQLException e) {
                connection.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("reserve failed: " + e.getMessage(), e);
        }
    }

    private void post(String url, Map<String, Object> body) {
        rest.postForObject(url, body, Map.class);
    }

    private void writeLog(String sagaId, int stepNo, String stepName, String state, String detail) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement write = connection.prepareStatement(
                     "INSERT INTO saga_log (saga_id, step_no, step_name, state, detail) VALUES (?, ?, ?, ?, ?) " +
                     "ON DUPLICATE KEY UPDATE state = VALUES(state), detail = VALUES(detail)")) {
            write.setString(1, sagaId);
            write.setInt(2, stepNo);
            write.setString(3, stepName);
            write.setString(4, state);
            write.setString(5, detail);
            write.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("could not write saga_log for " + sagaId, e);
        }
    }

    private Map<String, String> lastStep(String sagaId) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement read = connection.prepareStatement(
                     "SELECT l.step_no, l.step_name, b.id AS booking_id " +
                     "  FROM saga_log l JOIN bookings b ON b.gid = l.saga_id " +
                     " WHERE l.saga_id = ? AND l.state = 'DONE' AND l.step_no < 100 " +
                     " ORDER BY l.step_no DESC LIMIT 1")) {
            read.setString(1, sagaId);
            try (var row = read.executeQuery()) {
                if (!row.next()) {
                    return Map.of();
                }
                return Map.of(
                        "step_no", String.valueOf(row.getInt("step_no")),
                        "step_name", row.getString("step_name"),
                        "booking_id", String.valueOf(row.getLong("booking_id")),
                        "amount", "120.00");
            }
        } catch (SQLException e) {
            throw new IllegalStateException("could not read saga_log for " + sagaId, e);
        }
    }
}
