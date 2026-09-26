package com.demo.booking.services;

import com.demo.booking.model.BookingRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Every way we know how to book a room, from the broken one to the safe ones.
 *
 * The four public methods are meant to be read side by side. They differ only
 * in how they stop a second booking, never in what they write.
 */
@Service
public class BookingService {

    private static final Logger log = LoggerFactory.getLogger(BookingService.class);

    /**
     * Two bookings overlap when each starts before the other ends. There is no
     * UNIQUE constraint that expresses this, which is the whole reason write
     * skew is possible here. DDIA p.249.
     */
    private static final String OVERLAP_COUNT =
            "SELECT COUNT(*) FROM bookings " +
            " WHERE room_id = ? AND status <> 'CANCELLED' " +
            "   AND starts_at < ? AND ends_at > ?";

    private static final String INSERT_BOOKING =
            "INSERT INTO bookings (room_id, user_id, starts_at, ends_at, status, gid, fence_token) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?)";

    private final DataSource dataSource;

    public BookingService(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    // ------------------------------------------------------------------
    // 1. the broken one
    // ------------------------------------------------------------------

    /**
     * Check, then insert, under REPEATABLE READ. Run two of these at once and
     * both see zero overlapping bookings, because neither can see the other's
     * uncommitted row, and there is no row to lock yet. Both insert.
     *
     * @param pauseMs widens the window so a human can lose the race on purpose
     */
    public long bookNaive(BookingRequest request, long pauseMs) {
        try (Connection connection = dataSource.getConnection()) {
            connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            connection.setAutoCommit(false);
            try {
                int overlapping = countOverlapping(connection, request);
                log.info("[naive] room {} between {} and {}, found {} overlapping",
                        request.roomId(), request.startsAt(), request.endsAt(), overlapping);

                if (overlapping > 0) {
                    connection.rollback();
                    throw new IllegalStateException("room already booked");
                }
                sleep(pauseMs);

                long id = insert(connection, request, "CONFIRMED", null, 0);
                connection.commit();
                log.info("[naive] booking {} written", id);
                return id;
            } catch (SQLException e) {
                connection.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("naive booking failed: " + e.getMessage(), e);
        }
    }

    // ------------------------------------------------------------------
    // 2. materializing conflicts
    // ------------------------------------------------------------------

    /**
     * Lock the 15 minute slot rows that this booking covers before looking at
     * anything. room_slot_lock holds no booking data at all, it exists purely so
     * there is something concrete to take a lock on. DDIA p.251.
     *
     * The second request now waits on the first, and when it wakes up the row it
     * was checking for is there.
     */
    public long bookMaterialized(BookingRequest request, long pauseMs) {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                int locked = lockSlots(connection, request);
                log.info("[materialized] locked {} slot rows for room {}", locked, request.roomId());

                int overlapping = countOverlapping(connection, request);
                if (overlapping > 0) {
                    connection.rollback();
                    throw new IllegalStateException("room already booked");
                }
                sleep(pauseMs);

                long id = insert(connection, request, "CONFIRMED", null, 0);
                connection.commit();
                log.info("[materialized] booking {} written", id);
                return id;
            } catch (SQLException e) {
                connection.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("materialized booking failed: " + e.getMessage(), e);
        }
    }

    // ------------------------------------------------------------------
    // 3. serializable, with the retry the book says you need
    // ------------------------------------------------------------------

    /**
     * No helper table. InnoDB at SERIALIZABLE turns plain reads into locking
     * reads and uses next-key locks, so the gap the second transaction wants to
     * insert into is already locked. DDIA p.257 to 260.
     *
     * One of the two transactions dies, so the caller has to retry. That retry
     * is not a detail, it is the cost of 2PL.
     */
    public long bookSerializable(BookingRequest request, long pauseMs, int maxAttempts) {
        SQLException last = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try (Connection connection = dataSource.getConnection()) {
                connection.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);
                connection.setAutoCommit(false);
                try {
                    int overlapping = countOverlapping(connection, request);
                    if (overlapping > 0) {
                        connection.rollback();
                        throw new IllegalStateException("room already booked");
                    }
                    sleep(pauseMs);

                    long id = insert(connection, request, "CONFIRMED", null, 0);
                    connection.commit();
                    log.info("[serializable] booking {} written on attempt {}", id, attempt);
                    return id;
                } catch (SQLException e) {
                    connection.rollback();
                    throw e;
                }
            } catch (SQLException e) {
                last = e;
                log.warn("[serializable] attempt {} aborted by the database, {}", attempt, e.getMessage());
            }
        }
        throw new IllegalStateException(
                "gave up after " + maxAttempts + " attempts: " + (last == null ? "" : last.getMessage()), last);
    }

    // ------------------------------------------------------------------
    // 4. fencing token
    // ------------------------------------------------------------------

    /**
     * The resource checks the token itself, inside the same transaction as the
     * insert. A client that was paused past its lease still believes it holds
     * the lock, so asking the client to check is not a fix. DDIA p.303.
     */
    public long bookFenced(BookingRequest request, long token) {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                int accepted;
                try (PreparedStatement fence = connection.prepareStatement(
                        "UPDATE rooms SET fence_token = ? WHERE room_id = ? AND fence_token < ?")) {
                    fence.setLong(1, token);
                    fence.setInt(2, request.roomId());
                    fence.setLong(3, token);
                    accepted = fence.executeUpdate();
                }

                if (accepted == 0) {
                    connection.rollback();
                    log.warn("[fenced] REJECTED token {} for room {}, a newer token already wrote here",
                            token, request.roomId());
                    throw new IllegalStateException("stale fencing token " + token);
                }

                long id = insert(connection, request, "CONFIRMED", null, token);
                connection.commit();
                log.info("[fenced] accepted token {}, booking {} written", token, id);
                return id;
            } catch (SQLException e) {
                connection.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("fenced booking failed: " + e.getMessage(), e);
        }
    }

    // ------------------------------------------------------------------
    // shared pieces, also used by the 2PC and saga paths
    // ------------------------------------------------------------------

    public int countOverlapping(Connection connection, BookingRequest request) throws SQLException {
        try (PreparedStatement check = connection.prepareStatement(OVERLAP_COUNT)) {
            check.setInt(1, request.roomId());
            check.setString(2, request.endsAt());
            check.setString(3, request.startsAt());
            try (ResultSet row = check.executeQuery()) {
                row.next();
                return row.getInt(1);
            }
        }
    }

    /** Locks every 15 minute slot row the booking touches. */
    public int lockSlots(Connection connection, BookingRequest request) throws SQLException {
        try (PreparedStatement lock = connection.prepareStatement(
                "SELECT slot_start FROM room_slot_lock " +
                " WHERE room_id = ? AND slot_start >= ? AND slot_start < ? " +
                " ORDER BY slot_start FOR UPDATE")) {
            lock.setInt(1, request.roomId());
            lock.setString(2, request.startsAt());
            lock.setString(3, request.endsAt());
            try (ResultSet rows = lock.executeQuery()) {
                int n = 0;
                while (rows.next()) {
                    n++;
                }
                return n;
            }
        }
    }

    public long insert(Connection connection, BookingRequest request, String status, String gid, long token)
            throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement(INSERT_BOOKING, Statement.RETURN_GENERATED_KEYS)) {
            insert.setInt(1, request.roomId());
            insert.setInt(2, request.userId());
            insert.setString(3, request.startsAt());
            insert.setString(4, request.endsAt());
            insert.setString(5, status);
            insert.setString(6, gid);
            insert.setLong(7, token);
            insert.executeUpdate();
            try (ResultSet keys = insert.getGeneratedKeys()) {
                keys.next();
                return keys.getLong(1);
            }
        }
    }

    /**
     * Same insert, but with the id chosen by the caller.
     *
     * The 2PC path needs this: payment and accounting have to be told which
     * booking they are charging for while our own branch is still only prepared,
     * so the id cannot come from AUTO_INCREMENT after the fact.
     */
    public void insertWithId(Connection connection, long bookingId, BookingRequest request,
                             String status, String gid) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO bookings (id, room_id, user_id, starts_at, ends_at, status, gid, fence_token) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, 0)")) {
            insert.setLong(1, bookingId);
            insert.setInt(2, request.roomId());
            insert.setInt(3, request.userId());
            insert.setString(4, request.startsAt());
            insert.setString(5, request.endsAt());
            insert.setString(6, status);
            insert.setString(7, gid);
            insert.executeUpdate();
        }
    }

    public void setStatus(long bookingId, String status) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement update = connection.prepareStatement(
                     "UPDATE bookings SET status = ? WHERE id = ?")) {
            update.setString(1, status);
            update.setLong(2, bookingId);
            update.executeUpdate();
            log.info("booking {} is now {}", bookingId, status);
        } catch (SQLException e) {
            throw new IllegalStateException("could not set status on booking " + bookingId, e);
        }
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
