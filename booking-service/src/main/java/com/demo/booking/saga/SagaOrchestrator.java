package com.demo.booking.saga;

import com.demo.booking.bookings.BookingRepository;
import com.demo.booking.db.Database;
import com.demo.booking.model.BookingRejected;
import com.demo.booking.model.BookingRequest;
import com.demo.booking.util.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Four steps, each committed on its own. Nothing is atomic and nothing is
 * isolated: between step 1 and step 4 a reader sees a PENDING booking and money
 * that is already gone. PENDING is the lock, other bookings treat it as taken.
 */
@Service
public class SagaOrchestrator {
    private static final Logger logger = LoggerFactory.getLogger(SagaOrchestrator.class);

    private final Database database;
    private final BookingRepository bookings;
    private final SagaParticipants participants;
    private final SagaLog sagaLog;

    public SagaOrchestrator(Database database, BookingRepository bookings,
                            SagaParticipants participants, SagaLog sagaLog) {
        this.database = database;
        this.bookings = bookings;
        this.participants = participants;
        this.sagaLog = sagaLog;
    }

    /**
     * @param failAt fail before this step number, 0 for none
     * @param killAt stop dead after this step number, nothing compensated
     */
    public Map<String, Object> book(BookingRequest request, BigDecimal amount, int failAt, int killAt) {
        Timer whole = new Timer();
        String sagaId = "sg-" + UUID.randomUUID().toString().substring(0, 8);
        long bookingId = reserve(sagaId, request, amount);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("sagaId", sagaId);
        result.put("bookingId", bookingId);

        Deque<SagaStep> completed = new ArrayDeque<>();
        completed.push(new SagaStep("reserve", () -> {
        }, () -> bookings.updateStatus(bookingId, "CANCELLED")));

        List<SagaStep> steps = remainingSteps(sagaId, bookingId, request, amount);

        for (int index = 0; index < steps.size(); index++) {
            int stepNo = index + 2;
            SagaStep step = steps.get(index);

            if (failAt == stepNo) {
                logger.error("{} step {} {} failing on purpose", sagaId, stepNo, step.name());
                sagaLog.record(sagaId, stepNo, step.name(), "FAILED", bookingId, amount);
                compensate(sagaId, completed);
                result.put("state", "COMPENSATED");
                result.put("tookMillis", whole.elapsedMillis());
                return result;
            }

            sagaLog.record(sagaId, stepNo, step.name(), "STARTED", bookingId, amount);
            Timer stepTimer = new Timer();
            try {
                step.action().run();
            } catch (RuntimeException e) {
                logger.error("{} step {} {} failed, {}", sagaId, stepNo, step.name(), e.getMessage());
                sagaLog.record(sagaId, stepNo, step.name(), "FAILED", bookingId, amount);
                compensate(sagaId, completed);
                result.put("state", "COMPENSATED");
                return result;
            }
            sagaLog.record(sagaId, stepNo, step.name(), "DONE", bookingId, amount);
            logger.info("{} step {} {} done in {}ms", sagaId, stepNo, step.name(), stepTimer.elapsedMillis());
            completed.push(step);

            if (killAt == stepNo) {
                logger.error("{} orchestrator dies here. The booking is PENDING and the money is gone. "
                        + "Nothing is compensated.", sagaId);
                result.put("state", "ABANDONED");
                return result;
            }
        }

        logger.info("{} confirmed in {}ms", sagaId, whole.elapsedMillis());
        result.put("state", "CONFIRMED");
        result.put("tookMillis", whole.elapsedMillis());
        return result;
    }

    private List<SagaStep> remainingSteps(String sagaId, long bookingId,
                                          BookingRequest request, BigDecimal amount) {
        return List.of(
                new SagaStep("charge",
                        () -> participants.charge(sagaId, bookingId, request.userId(), amount),
                        () -> participants.refund(sagaId, bookingId)),
                new SagaStep("ledger",
                        () -> participants.postRevenue(sagaId, bookingId, amount),
                        () -> participants.postReversal(sagaId, bookingId, amount)),
                SagaStep.terminal("confirm",
                        () -> bookings.updateStatus(bookingId, "CONFIRMED")));
    }

    /** Step 1. Also the semantic lock: a PENDING row makes the room taken. */
    private long reserve(String sagaId, BookingRequest request, BigDecimal amount) {
        long bookingId = database.transaction(connection -> {
            bookings.lockSlots(connection, request);
            if (bookings.overlaps(connection, request)) {
                throw new BookingRejected("room already booked");
            }
            return bookings.insert(connection, request, "PENDING", sagaId, 0);
        });
        logger.info("{} step 1 reserve, booking {} is PENDING", sagaId, bookingId);
        sagaLog.record(sagaId, 1, "reserve", "DONE", bookingId, amount);
        return bookingId;
    }

    /** Undo the completed steps in reverse. A failed compensation must be retried. */
    private void compensate(String sagaId, Deque<SagaStep> completed) {
        Timer timer = new Timer();
        logger.warn("{} compensating {} completed steps, in reverse", sagaId, completed.size());
        int stepNo = 100 + completed.size();
        while (!completed.isEmpty()) {
            SagaStep step = completed.pop();
            try {
                step.compensation().run();
                sagaLog.record(sagaId, stepNo--, step.name(), "COMPENSATED", null, null);
            } catch (RuntimeException e) {
                logger.error("{} compensation for {} failed, {}. This needs a retry.",
                        sagaId, step.name(), e.getMessage());
            }
        }
        logger.warn("{} compensation finished in {}ms", sagaId, timer.elapsedMillis());
    }

    /** Restart, read the log, carry on from the last completed step. */
    public Map<String, Object> resume(String sagaId) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("sagaId", sagaId);

        var progress = sagaLog.lastCompleted(sagaId);
        if (progress.isEmpty()) {
            result.put("state", "UNKNOWN_SAGA");
            return result;
        }

        SagaLog.SagaProgress state = progress.get();
        logger.info("{} resuming after step {}", sagaId, state.lastStep());

        if (state.lastStep() < 3) {
            participants.postRevenue(sagaId, state.bookingId(), state.amount());
            sagaLog.record(sagaId, 3, "ledger", "DONE", state.bookingId(), state.amount());
        }
        if (state.lastStep() < 4) {
            bookings.updateStatus(state.bookingId(), "CONFIRMED");
            sagaLog.record(sagaId, 4, "confirm", "DONE", state.bookingId(), state.amount());
        }
        result.put("state", "CONFIRMED");
        return result;
    }
}
