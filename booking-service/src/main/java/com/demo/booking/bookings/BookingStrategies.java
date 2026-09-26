package com.demo.booking.bookings;

import com.demo.booking.db.Database;
import com.demo.booking.model.BookingRejected;
import com.demo.booking.model.BookingRequest;
import com.demo.booking.util.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * Four ways to book the same room. They differ by one line each, which is the
 * whole point of reading them side by side.
 */
@Service
public class BookingStrategies {
    private static final Logger logger = LoggerFactory.getLogger(BookingStrategies.class);

    private final Database database;
    private final BookingRepository bookings;

    public BookingStrategies(Database database, BookingRepository bookings) {
        this.database = database;
        this.bookings = bookings;
    }

    /** Check then insert. Two at once both see zero and both write. DDIA p.249. */
    public long naive(BookingRequest request, long pauseMs) {
        return timed("naive", () -> database.transaction(connection -> {
            rejectIfTaken(connection, request);
            pause(pauseMs);
            return bookings.insert(connection, request, "CONFIRMED", null, 0);
        }));
    }

    /** Lock the slot rows first, so there is something to wait on. DDIA p.251. */
    public long materialized(BookingRequest request, long pauseMs) {
        return timed("materialized", () -> database.transaction(connection -> {
            bookings.lockSlots(connection, request);
            rejectIfTaken(connection, request);
            pause(pauseMs);
            return bookings.insert(connection, request, "CONFIRMED", null, 0);
        }));
    }

    /** Let the database do it. One transaction aborts, so we retry. DDIA p.257. */
    public long serializable(BookingRequest request, long pauseMs, int attempts) {
        Timer timer = new Timer();
        for (int attempt = 1; attempt <= attempts; attempt++) {
            try {
                return database.transaction(Connection.TRANSACTION_SERIALIZABLE, connection -> {
                    rejectIfTaken(connection, request);
                    pause(pauseMs);
                    return bookings.insert(connection, request, "CONFIRMED", null, 0);
                });
            } catch (BookingRejected e) {
                logger.info("serializable rejected after {}ms, {}", timer.elapsedMillis(), e.getMessage());
                throw e;
            } catch (RuntimeException e) {
                logger.warn("attempt {} aborted by the database after {}ms, {}",
                        attempt, timer.elapsedMillis(), e.getMessage());
            }
        }
        throw new BookingRejected("gave up after " + attempts + " attempts");
    }

    /** The write carries a fencing token and the table checks it. DDIA p.303. */
    public long fenced(BookingRequest request, long token) {
        return timed("fenced", () -> database.transaction(connection -> {
            if (!bookings.acceptToken(connection, request.roomId(), token)) {
                throw new BookingRejected("stale fencing token " + token);
            }
            return bookings.insert(connection, request, "CONFIRMED", null, token);
        }));
    }

    /**
     * Runs an attempt and logs how long it took. Time spent here is mostly time
     * spent waiting on somebody else's lock, which is the thing worth seeing.
     *
     * @param strategy name shown in the log
     * @param attempt  the booking attempt
     * @return the new booking id
     */
    private long timed(String strategy, java.util.function.Supplier<Long> attempt) {
        Timer timer = new Timer();
        try {
            long id = attempt.get();
            logger.info("{} booked {} in {}ms", strategy, id, timer.elapsedMillis());
            return id;
        } catch (RuntimeException e) {
            logger.info("{} rejected after {}ms, {}", strategy, timer.elapsedMillis(), e.getMessage());
            throw e;
        }
    }

    private void rejectIfTaken(Connection connection, BookingRequest request) throws SQLException {
        if (bookings.overlaps(connection, request)) {
            throw new BookingRejected("room already booked");
        }
    }

    private static void pause(long millis) {
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
