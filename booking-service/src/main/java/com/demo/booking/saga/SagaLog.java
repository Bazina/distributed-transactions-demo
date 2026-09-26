package com.demo.booking.saga;

import com.demo.booking.db.Database;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Optional;

/** What the orchestrator reads after it restarts. */
@Repository
public class SagaLog {
    private final Database database;

    public SagaLog(Database database) {
        this.database = database;
    }

    public void record(String sagaId, int stepNo, String stepName, String state,
                       Long bookingId, BigDecimal amount) {
        database.transaction(connection -> {
            try (PreparedStatement write = connection.prepareStatement(
                    "INSERT INTO saga_log (saga_id, step_no, step_name, state, booking_id, amount) "
                    + "VALUES (?, ?, ?, ?, ?, ?) "
                    + "ON DUPLICATE KEY UPDATE state = VALUES(state)")) {
                write.setString(1, sagaId);
                write.setInt(2, stepNo);
                write.setString(3, stepName);
                write.setString(4, state);
                write.setObject(5, bookingId);
                write.setBigDecimal(6, amount);
                return write.executeUpdate();
            }
        });
    }

    public Optional<SagaProgress> lastCompleted(String sagaId) {
        return database.transaction(connection -> {
            try (PreparedStatement read = connection.prepareStatement(
                    "SELECT step_no, booking_id, amount FROM saga_log "
                    + "WHERE saga_id = ? AND state = 'DONE' AND step_no < 100 "
                    + "ORDER BY step_no DESC LIMIT 1")) {
                read.setString(1, sagaId);
                try (ResultSet row = read.executeQuery()) {
                    if (!row.next()) {
                        return Optional.<SagaProgress>empty();
                    }
                    return Optional.of(new SagaProgress(
                            row.getInt("step_no"), row.getLong("booking_id"), row.getBigDecimal("amount")));
                }
            }
        });
    }

    public record SagaProgress(int lastStep, long bookingId, BigDecimal amount) {
    }
}
