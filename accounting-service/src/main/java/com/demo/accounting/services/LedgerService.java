package com.demo.accounting.services;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

/**
 * The ledger is append only. Nothing in here ever deletes or updates a row.
 *
 * That is the honest shape of a compensation: you cannot un-write an accounting
 * entry, you write the opposite one and both stay in the history.
 */
@Service
public class LedgerService {

    private static final Logger log = LoggerFactory.getLogger(LedgerService.class);

    private final DataSource dataSource;

    public LedgerService(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /** UNIQUE(booking_id, kind) makes a replay a no-op. */
    public void post(Connection connection, String gid, long bookingId, String kind, BigDecimal amount)
            throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT IGNORE INTO ledger (booking_id, gid, kind, amount) VALUES (?, ?, ?, ?)")) {
            insert.setLong(1, bookingId);
            insert.setString(2, gid);
            insert.setString(3, kind);
            insert.setBigDecimal(4, amount);
            insert.executeUpdate();
        }
        log.info("[gid={}] ledger {} {} for booking {}", gid, kind, amount, bookingId);
    }

    /** Saga version: commits on its own, immediately. */
    public void postNow(String gid, long bookingId, String kind, BigDecimal amount) {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                post(connection, gid, bookingId, kind, amount);
                connection.commit();
            } catch (SQLException e) {
                connection.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("ledger post failed: " + e.getMessage(), e);
        }
    }
}
