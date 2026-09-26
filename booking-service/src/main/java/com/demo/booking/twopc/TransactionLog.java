package com.demo.booking.twopc;

import com.demo.booking.db.Database;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.LinkedHashMap;
import java.util.Map;

/** The coordinator's memory. Writing a decision here is the commit point. */
@Repository
public class TransactionLog {
    private final Database database;

    public TransactionLog(Database database) {
        this.database = database;
    }

    public void record(String transactionId, String decision) {
        database.transaction(connection -> {
            try (PreparedStatement write = connection.prepareStatement(
                    "INSERT INTO tx_log (transaction_id, decision) VALUES (?, ?) "
                    + "ON DUPLICATE KEY UPDATE decision = VALUES(decision)")) {
                write.setString(1, transactionId);
                write.setString(2, decision);
                return write.executeUpdate();
            }
        });
    }

    /** Transaction id to decision, for everything not already aborted. */
    public Map<String, String> unresolved() {
        return database.transaction(connection -> {
            Map<String, String> rows = new LinkedHashMap<>();
            try (PreparedStatement read = connection.prepareStatement(
                    "SELECT transaction_id, decision FROM tx_log WHERE decision <> 'ABORT' ORDER BY updated_at");
                 ResultSet result = read.executeQuery()) {
                while (result.next()) {
                    rows.put(result.getString(1), result.getString(2));
                }
            }
            return rows;
        });
    }
}
