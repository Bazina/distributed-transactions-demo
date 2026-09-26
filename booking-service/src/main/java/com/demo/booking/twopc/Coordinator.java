package com.demo.booking.twopc;

import com.demo.booking.bookings.BookingRepository;
import com.demo.booking.model.BookingRequest;
import com.demo.booking.model.FailMode;
import com.demo.booking.xa.XaParticipant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.demo.booking.util.Timer;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Two-phase commit. booking-service is the coordinator and also a participant,
 * since it owns bookingdb.
 *
 * Writing the decision to the log is the commit point. Before it anyone may
 * abort, after it the decision is final and phase two is retried until it lands.
 */
@Service
public class Coordinator {
    private static final Logger logger = LoggerFactory.getLogger(Coordinator.class);

    private final XaParticipant localBranch;
    private final BookingRepository bookings;
    private final TransactionLog transactionLog;
    private final List<RemoteParticipant> remotes;

    private volatile FailMode failMode = FailMode.NONE;

    public Coordinator(XaParticipant localBranch, BookingRepository bookings,
                       TransactionLog transactionLog, List<RemoteParticipant> remotes) {
        this.localBranch = localBranch;
        this.bookings = bookings;
        this.transactionLog = transactionLog;
        this.remotes = remotes;
    }

    public void setFailMode(FailMode mode) {
        this.failMode = mode;
        logger.warn("fail mode is now {}", mode);
    }

    public Map<String, Object> book(BookingRequest request, BigDecimal amount) {
        Timer whole = new Timer();
        String transactionId = "bk-" + UUID.randomUUID().toString().substring(0, 8);
        long bookingId = bookings.nextId();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("transactionId", transactionId);
        result.put("bookingId", bookingId);

        logger.info("{} begin, room {} for user {}, amount {}",
                transactionId, request.roomId(), request.userId(), amount);
        transactionLog.record(transactionId, "PREPARING");

        Timer phaseOne = new Timer();
        List<RemoteParticipant> prepared = collectVotes(transactionId, bookingId, request, amount);
        logger.info("{} phase 1 finished in {}ms", transactionId, phaseOne.elapsedMillis());
        boolean localVote = prepared != null;
        boolean allYes = localVote && prepared.size() == remotes.size();

        if (allYes && failMode == FailMode.CRASH_AFTER_VOTES) {
            logger.error("{} everyone voted yes and I am walking away. "
                    + "Every participant is now in doubt and holding locks.", transactionId);
            result.put("state", "COORDINATOR_CRASHED");
            result.put("tookMillis", whole.elapsedMillis());
            return result;
        }

        String decision = allYes ? "COMMIT" : "ABORT";
        transactionLog.record(transactionId, decision);
        logger.info("{} decision {} is on disk, no going back", transactionId, decision);

        Timer phaseTwo = new Timer();
        applyDecision(transactionId, decision, localVote, prepared);
        logger.info("{} phase 2 finished in {}ms, {}ms in total",
                transactionId, phaseTwo.elapsedMillis(), whole.elapsedMillis());

        result.put("state", allYes ? "COMMITTED" : "ABORTED");
        result.put("tookMillis", whole.elapsedMillis());
        return result;
    }

    /** Phase one. Returns the participants that voted yes, or null if we did not. */
    private List<RemoteParticipant> collectVotes(String transactionId, long bookingId,
                                                 BookingRequest request, BigDecimal amount) {
        boolean localVote = localBranch.prepare(transactionId, connection -> {
            if (bookings.overlaps(connection, request)) {
                throw new SQLException("room already booked");
            }
            bookings.insertWithId(connection, bookingId, request, "CONFIRMED", transactionId);
        });
        if (!localVote) {
            return null;
        }

        Map<String, Object> ask = Map.of(
                "transactionId", transactionId,
                "bookingId", bookingId,
                "userId", request.userId(),
                "amount", amount);

        List<RemoteParticipant> yes = new ArrayList<>();
        for (RemoteParticipant remote : remotes) {
            if (!remote.prepare(ask)) {
                return yes;
            }
            yes.add(remote);
        }
        return yes;
    }

    /** Phase two. Every call is safe to repeat, so a retry costs nothing. */
    private void applyDecision(String transactionId, String decision,
                               boolean localPrepared, List<RemoteParticipant> prepared) {
        boolean commit = "COMMIT".equals(decision);

        if (localPrepared) {
            if (commit) {
                localBranch.commit(transactionId);
            } else {
                localBranch.rollback(transactionId);
            }
        }
        for (RemoteParticipant remote : prepared == null ? List.<RemoteParticipant>of() : prepared) {
            if (commit && failMode == FailMode.COMMIT_LOST_TO_PAYMENT && "payment".equals(remote.name())) {
                logger.error("{} the commit message to payment is lost. The decision still stands.", transactionId);
            } else if (commit) {
                remote.commit(transactionId);
            } else {
                remote.rollback(transactionId);
            }
        }
    }

    /** Wake up, read the log, finish what was decided. Anything undecided aborts. */
    public Map<String, String> recover() {
        Map<String, String> summary = new LinkedHashMap<>();
        transactionLog.unresolved().forEach((transactionId, decision) -> {
            String outcome = "PREPARING".equals(decision) ? "ABORT" : decision;
            if (!outcome.equals(decision)) {
                transactionLog.record(transactionId, outcome);
            }
            logger.info("{} recovering as {}", transactionId, outcome);
            applyDecision(transactionId, outcome, true, remotes);
            summary.put(transactionId, outcome);
        });
        return summary;
    }

    public Map<String, Object> inDoubt() {
        Map<String, Object> all = new LinkedHashMap<>();
        all.put("booking", localBranch.inDoubt());
        remotes.forEach(remote -> all.put(remote.name(), remote.inDoubt()));
        return all;
    }
}
