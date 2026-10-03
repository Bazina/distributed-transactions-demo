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
 * that is already gone.
 *
 * There is one definition of the steps, and both a fresh saga and a resumed one
 * walk it. A resume that re-listed the steps by hand would drift from the real
 * flow and skip whichever step the author was not thinking about.
 */
@Service
public class SagaOrchestrator {
    private static final Logger logger = LoggerFactory.getLogger(SagaOrchestrator.class);

    /** Step 1 is reserve, so the step list below starts at step 2. */
    private static final int FIRST_LISTED_STEP = 2;

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
     * @param killAt stop after this step number without compensating, 0 for none
     */
    public Map<String, Object> book(BookingRequest request, BigDecimal amount, int failAt, int killAt) {
        Timer whole = new Timer();
        String sagaId = "sg-" + UUID.randomUUID().toString().substring(0, 8);
        long bookingId = reserve(sagaId, request, amount);

        if (killAt == 1) {
            logger.error("{} orchestrator dies after reserve. The room is held and nobody has paid.", sagaId);
            return abandoned(sagaId, bookingId, whole);
        }
        return run(sagaId, bookingId, request, amount, FIRST_LISTED_STEP, failAt, killAt, whole);
    }

    /**
     * Restart, read the log, carry on from the step after the last completed one.
     *
     * Every step is idempotent, so re-running one that did land is a no-op
     * rather than a second charge.
     *
     * @param sagaId the saga to continue
     */
    public Map<String, Object> resume(String sagaId) {
        Timer whole = new Timer();
        var progress = sagaLog.lastCompleted(sagaId);
        if (progress.isEmpty()) {
            return Map.of("sagaId", sagaId, "state", "UNKNOWN_SAGA");
        }

        SagaLog.SagaProgress state = progress.get();
        var booking = bookings.find(state.bookingId());
        if (booking.isEmpty()) {
            return Map.of("sagaId", sagaId, "state", "UNKNOWN_BOOKING");
        }

        BookingRepository.BookingRow row = booking.get();
        BookingRequest request = new BookingRequest(
                row.roomId(), row.userId(), row.startsAt(), row.endsAt());

        logger.info("{} resuming at step {}, last completed was {}",
                sagaId, state.lastStep() + 1, state.lastStep());
        return run(sagaId, state.bookingId(), request, state.amount(),
                state.lastStep() + 1, 0, 0, whole);
    }

    /**
     * Walks the step list from one step onwards. Used by a fresh saga and by a
     * resumed one, so there is only ever one description of the flow.
     *
     * @param fromStep the first step to run, counted from 1
     */
    private Map<String, Object> run(String sagaId, long bookingId, BookingRequest request,
                                    BigDecimal amount, int fromStep, int failAt, int killAt, Timer whole) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("sagaId", sagaId);
        result.put("bookingId", bookingId);

        List<SagaStep> steps = listedSteps(sagaId, bookingId, request, amount);
        Deque<SagaStep> completed = alreadyDone(steps, bookingId, fromStep);

        for (int index = fromStep - FIRST_LISTED_STEP; index < steps.size(); index++) {
            int stepNo = index + FIRST_LISTED_STEP;
            SagaStep step = steps.get(index);

            if (failAt == stepNo) {
                logger.error("{} step {} {} failing on purpose", sagaId, stepNo, step.name());
                sagaLog.record(sagaId, stepNo, step.name(), "FAILED", bookingId, amount);
                compensate(sagaId, completed);
                return finish(result, "COMPENSATED", whole);
            }

            sagaLog.record(sagaId, stepNo, step.name(), "STARTED", bookingId, amount);
            Timer stepTimer = new Timer();
            try {
                step.action().run();
            } catch (RuntimeException e) {
                logger.error("{} step {} {} failed, {}", sagaId, stepNo, step.name(), e.getMessage());
                sagaLog.record(sagaId, stepNo, step.name(), "FAILED", bookingId, amount);
                compensate(sagaId, completed);
                result.put("reason", String.valueOf(e.getMessage()));
                return finish(result, "COMPENSATED", whole);
            }
            sagaLog.record(sagaId, stepNo, step.name(), "DONE", bookingId, amount);
            logger.info("{} step {} {} done in {}ms", sagaId, stepNo, step.name(), stepTimer.elapsedMillis());
            completed.push(step);

            if (killAt == stepNo) {
                logger.error("{} orchestrator dies here. Nothing is compensated.", sagaId);
                return finish(result, "ABANDONED", whole);
            }
        }

        logger.info("{} confirmed in {}ms", sagaId, whole.elapsedMillis());
        return finish(result, "CONFIRMED", whole);
    }

    /** The steps after reserve, in order. The single description of the flow. */
    private List<SagaStep> listedSteps(String sagaId, long bookingId,
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

    /**
     * The steps that ran before we got here, newest first, so a failure after a
     * resume still compensates everything the saga ever did.
     */
    private Deque<SagaStep> alreadyDone(List<SagaStep> steps, long bookingId, int fromStep) {
        Deque<SagaStep> completed = new ArrayDeque<>();
        completed.push(new SagaStep("reserve", () -> {
        }, () -> bookings.updateStatus(bookingId, "CANCELLED")));
        for (int index = 0; index < fromStep - FIRST_LISTED_STEP; index++) {
            completed.push(steps.get(index));
        }
        return completed;
    }

    /** Step 1, and the only step that needs two different locks.
     *
     * lockSlots materializes the conflict so two sagas racing for the same room
     * cannot both reserve it. That lock goes when this transaction commits.
     *
     * The PENDING row is the semantic lock and it lasts for the whole saga,
     * because overlaps() counts anything not CANCELLED. A saga has no isolation
     * to lean on, so the status column has to be the lock.
     */
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

    private Map<String, Object> abandoned(String sagaId, long bookingId, Timer whole) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("sagaId", sagaId);
        result.put("bookingId", bookingId);
        return finish(result, "ABANDONED", whole);
    }

    private Map<String, Object> finish(Map<String, Object> result, String state, Timer whole) {
        result.put("state", state);
        result.put("tookMillis", whole.elapsedMillis());
        return result;
    }
}
