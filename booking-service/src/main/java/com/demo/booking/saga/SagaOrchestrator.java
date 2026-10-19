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
import java.util.Optional;
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

    public Map<String, Object> book(BookingRequest request, BigDecimal amount, FailureInjection failure) {
        Timer whole = new Timer();
        String sagaId = "sg-" + UUID.randomUUID().toString().substring(0, 8);
        long bookingId = reserve(sagaId, request, amount);

        if (failure.killsAfterReserve()) {
            logger.error("{} orchestrator dies after reserve. The room is held and nobody has paid.", sagaId);
            return abandoned(sagaId, bookingId, whole);
        }
        return run(sagaId, bookingId, request, amount, SagaStep.FIRST_AFTER_RESERVE, failure, whole);
    }

    /**
     * Restart, read the log, carry on from the step after the last completed one.
     *
     * Every step is idempotent, so re-running one that did land is a no-op
     * rather than a second charge.
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
                state.lastStep() + 1, FailureInjection.none(), whole);
    }

    /**
     * Walks the step list from one step onwards. Used by a fresh saga and by a
     * resumed one, so there is only ever one description of the flow.
     */
    private Map<String, Object> run(String sagaId, long bookingId, BookingRequest request,
                                    BigDecimal amount, int fromStep, FailureInjection failure, Timer whole) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("sagaId", sagaId);
        result.put("bookingId", bookingId);

        List<SagaStep> steps = listedSteps(sagaId, bookingId, request, amount);
        Deque<SagaStep> completed = alreadyDone(steps, bookingId, fromStep);

        for (SagaStep step : steps) {
            if (step.number() < fromStep) {
                continue;
            }

            Optional<String> failed = attempt(sagaId, step, bookingId, amount, failure);
            if (failed.isPresent()) {
                compensate(sagaId, completed);
                result.put("reason", failed.get());
                return finish(result, "COMPENSATED", whole);
            }
            completed.push(step);

            if (failure.killsAfter(step)) {
                logger.error("{} orchestrator dies here. Nothing is compensated.", sagaId);
                return finish(result, "ABANDONED", whole);
            }
        }

        logger.info("{} confirmed in {}ms", sagaId, whole.elapsedMillis());
        return finish(result, "CONFIRMED", whole);
    }

    /** Runs one step. Empty means it worked, otherwise the reason it did not. */
    private Optional<String> attempt(String sagaId, SagaStep step, long bookingId,
                                     BigDecimal amount, FailureInjection failure) {
        if (failure.failsBefore(step)) {
            logger.error("{} step {} {} failing on purpose", sagaId, step.number(), step.name());
            sagaLog.record(sagaId, step.number(), step.name(), "FAILED", bookingId, amount);
            return Optional.of("failed on purpose");
        }

        sagaLog.record(sagaId, step.number(), step.name(), "STARTED", bookingId, amount);
        Timer timer = new Timer();
        try {
            step.action().run();
        } catch (RuntimeException e) {
            logger.error("{} step {} {} failed, {}", sagaId, step.number(), step.name(), e.getMessage());
            sagaLog.record(sagaId, step.number(), step.name(), "FAILED", bookingId, amount);
            return Optional.of(String.valueOf(e.getMessage()));
        }

        sagaLog.record(sagaId, step.number(), step.name(), "DONE", bookingId, amount);
        logger.info("{} step {} {} done in {}ms", sagaId, step.number(), step.name(), timer.elapsedMillis());
        return Optional.empty();
    }

    /** The steps after reserve, in order. The single description of the flow. */
    private List<SagaStep> listedSteps(String sagaId, long bookingId,
                                       BookingRequest request, BigDecimal amount) {
        return List.of(
                new SagaStep(2, "charge",
                        () -> participants.charge(sagaId, bookingId, request.userId(), amount),
                        () -> participants.refund(sagaId, bookingId)),
                new SagaStep(3, "ledger",
                        () -> participants.postRevenue(sagaId, bookingId, amount),
                        () -> participants.postReversal(sagaId, bookingId, amount)),
                SagaStep.terminal(4, "confirm",
                        () -> bookings.updateStatus(bookingId, "CONFIRMED")));
    }

    /**
     * The steps that ran before we got here, newest first, so a failure after a
     * resume still compensates everything the saga ever did.
     */
    private Deque<SagaStep> alreadyDone(List<SagaStep> steps, long bookingId, int fromStep) {
        Deque<SagaStep> completed = new ArrayDeque<>();
        completed.push(reserveStep(bookingId));
        steps.stream()
                .filter(step -> step.number() < fromStep)
                .forEach(completed::push);
        return completed;
    }

    /** Reserve always ran, so here it is only its undo. */
    private SagaStep reserveStep(long bookingId) {
        return new SagaStep(SagaStep.RESERVE, "reserve",
                () -> {
                },
                () -> bookings.updateStatus(bookingId, "CANCELLED"));
    }

    /**
     * Step 1, and the only step that needs two different locks.
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
        logger.info("{} step {} reserve, booking {} is PENDING", sagaId, SagaStep.RESERVE, bookingId);
        sagaLog.record(sagaId, SagaStep.RESERVE, "reserve", "DONE", bookingId, amount);
        return bookingId;
    }

    /** Undo the completed steps in reverse. A failed compensation must be retried. */
    private void compensate(String sagaId, Deque<SagaStep> completed) {
        Timer timer = new Timer();
        logger.warn("{} compensating {} completed steps, in reverse", sagaId, completed.size());
        // Numbered upwards as they pop, so reading saga_log in order shows the
        // undo in the order it happened. Above 100 so lastCompleted ignores them.
        int stepNo = 101;
        while (!completed.isEmpty()) {
            SagaStep step = completed.pop();
            try {
                step.compensation().run();
                sagaLog.record(sagaId, stepNo++, step.name(), "COMPENSATED", null, null);
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
