package com.demo.accounting.ledger;

import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

/** All the SQL for the ledger. Append only, nothing here updates or deletes. */
@Repository
public class LedgerRepository {

    /**
     * UNIQUE(booking_id, kind) makes a replay a no-op.
     *
     * @return true if this call wrote the row
     */
    public boolean post(Connection connection, String transactionId, long bookingId,
                        String kind, BigDecimal amount) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT IGNORE INTO ledger (booking_id, transaction_id, kind, amount) VALUES (?, ?, ?, ?)")) {
            insert.setLong(1, bookingId);
            insert.setString(2, transactionId);
            insert.setString(3, kind);
            insert.setBigDecimal(4, amount);
            return insert.executeUpdate() == 1;
        }
    }
}
