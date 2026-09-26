package com.demo.booking.bookings;

import com.demo.booking.db.Database;
import com.demo.booking.model.BookingRequest;
import org.springframework.stereotype.Repository;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/** All the SQL for bookings. No policy, no transactions. */
@Repository
public class BookingRepository {
    private static final String OVERLAPS =
            "SELECT COUNT(*) FROM bookings "
            + "WHERE room_id = ? AND status <> 'CANCELLED' AND starts_at < ? AND ends_at > ?";

    private static final String LOCK_SLOTS =
            "SELECT slot_start FROM room_slot_lock "
            + "WHERE room_id = ? AND slot_start >= ? AND slot_start < ? "
            + "ORDER BY slot_start FOR UPDATE";

    private static final String INSERT =
            "INSERT INTO bookings (room_id, user_id, starts_at, ends_at, status, transaction_id, fence_token) "
            + "VALUES (?, ?, ?, ?, ?, ?, ?)";

    private static final String INSERT_WITH_ID =
            "INSERT INTO bookings (id, room_id, user_id, starts_at, ends_at, status, transaction_id, fence_token) "
            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)";

    private final Database database;

    public BookingRepository(Database database) {
        this.database = database;
    }

    public boolean overlaps(Connection connection, BookingRequest request) throws SQLException {
        try (PreparedStatement check = connection.prepareStatement(OVERLAPS)) {
            check.setInt(1, request.roomId());
            check.setString(2, request.endsAt());
            check.setString(3, request.startsAt());
            try (ResultSet row = check.executeQuery()) {
                row.next();
                return row.getInt(1) > 0;
            }
        }
    }

    /** Locks every slot row the booking covers, so the phantom has a body. */
    public void lockSlots(Connection connection, BookingRequest request) throws SQLException {
        try (PreparedStatement lock = connection.prepareStatement(LOCK_SLOTS)) {
            lock.setInt(1, request.roomId());
            lock.setString(2, request.startsAt());
            lock.setString(3, request.endsAt());
            lock.executeQuery().close();
        }
    }

    /**
     * Lets AUTO_INCREMENT choose the id. Two concurrent inserts must both be
     * able to succeed, otherwise the write skew demo would be stopped by a
     * duplicate key rather than by isolation.
     *
     * @return the new booking id
     */
    public long insert(Connection connection, BookingRequest request,
                       String status, String transactionId, long fenceToken) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement(INSERT, Statement.RETURN_GENERATED_KEYS)) {
            insert.setInt(1, request.roomId());
            insert.setInt(2, request.userId());
            insert.setString(3, request.startsAt());
            insert.setString(4, request.endsAt());
            insert.setString(5, status);
            insert.setString(6, transactionId);
            insert.setLong(7, fenceToken);
            insert.executeUpdate();
            try (ResultSet keys = insert.getGeneratedKeys()) {
                keys.next();
                return keys.getLong(1);
            }
        }
    }

    /**
     * Uses an id chosen by the caller. Two-phase commit needs this: payment and
     * accounting have to be told which booking they are for while our own
     * branch is still only prepared.
     */
    public void insertWithId(Connection connection, long id, BookingRequest request,
                             String status, String transactionId) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement(INSERT_WITH_ID)) {
            insert.setLong(1, id);
            insert.setInt(2, request.roomId());
            insert.setInt(3, request.userId());
            insert.setString(4, request.startsAt());
            insert.setString(5, request.endsAt());
            insert.setString(6, status);
            insert.setString(7, transactionId);
            insert.setLong(8, 0);
            insert.executeUpdate();
        }
    }

    /**
     * Accepts the write only if this token is newer than anything the room has
     * seen. The check lives here, in the resource, because a paused client
     * cannot know its lease expired. DDIA p.303.
     */
    public boolean acceptToken(Connection connection, int roomId, long token) throws SQLException {
        try (PreparedStatement fence = connection.prepareStatement(
                "UPDATE rooms SET fence_token = ? WHERE room_id = ? AND fence_token < ?")) {
            fence.setLong(1, token);
            fence.setInt(2, roomId);
            fence.setLong(3, token);
            return fence.executeUpdate() == 1;
        }
    }

    public long nextId(Connection connection) throws SQLException {
        try (PreparedStatement next = connection.prepareStatement("SELECT COALESCE(MAX(id), 0) + 1 FROM bookings");
             ResultSet row = next.executeQuery()) {
            row.next();
            return row.getLong(1);
        }
    }

    public long nextId() {
        return database.transaction(this::nextId);
    }

    public void updateStatus(long bookingId, String status) {
        database.transaction(connection -> {
            try (PreparedStatement update = connection.prepareStatement(
                    "UPDATE bookings SET status = ? WHERE id = ?")) {
                update.setString(1, status);
                update.setLong(2, bookingId);
                return update.executeUpdate();
            }
        });
    }
}
