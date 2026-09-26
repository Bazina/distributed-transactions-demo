package com.demo.payment.services;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

/**
 * The only writes this service makes. Every one of them is a row and a log line,
 * there is no business logic in the demo on purpose.
 */
@Service
public class PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);

    private final DataSource dataSource;

    public PaymentService(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /**
     * Take the money. The CHECK on wallets.balance is what turns "not enough
     * money" into a real failure rather than a negative balance we never notice.
     *
     * UNIQUE(booking_id) makes a replayed charge a no-op instead of a double
     * charge, which is the idempotent receiver the saga needs.
     */
    public void charge(Connection connection, String gid, long bookingId, int userId, BigDecimal amount)
            throws SQLException {

        // Claim the booking id FIRST. The unique key has to gate the money
        // movement, not just deduplicate the row afterwards. Debiting first and
        // relying on ON DUPLICATE KEY to absorb the replay keeps one payment row
        // and takes the money twice, which is the worst of both.
        int claimed;
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT IGNORE INTO payments (booking_id, gid, user_id, amount, status) " +
                "VALUES (?, ?, ?, ?, 'CHARGED')")) {
            insert.setLong(1, bookingId);
            insert.setString(2, gid);
            insert.setInt(3, userId);
            insert.setBigDecimal(4, amount);
            claimed = insert.executeUpdate();
        }

        if (claimed == 0) {
            log.info("[gid={}] booking {} was already charged, no-op", gid, bookingId);
            return;
        }

        try (PreparedStatement debit = connection.prepareStatement(
                "UPDATE wallets SET balance = balance - ? WHERE user_id = ?")) {
            debit.setBigDecimal(1, amount);
            debit.setInt(2, userId);
            debit.executeUpdate();
        }

        log.info("[gid={}] charged {} from user {} for booking {}", gid, amount, userId, bookingId);
    }

    /**
     * Give the money back. This is a compensation, not an undo. The payment row
     * stays and changes status, so the history of what happened survives.
     */
    public void refund(String gid, long bookingId) {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                BigDecimal amount;
                int userId;
                try (PreparedStatement find = connection.prepareStatement(
                        "SELECT user_id, amount, status FROM payments WHERE booking_id = ? FOR UPDATE")) {
                    find.setLong(1, bookingId);
                    var row = find.executeQuery();
                    if (!row.next()) {
                        log.info("[gid={}] refund for booking {} had nothing to undo", gid, bookingId);
                        connection.rollback();
                        return;
                    }
                    if ("REFUNDED".equals(row.getString("status"))) {
                        log.info("[gid={}] booking {} was already refunded, no-op", gid, bookingId);
                        connection.rollback();
                        return;
                    }
                    userId = row.getInt("user_id");
                    amount = row.getBigDecimal("amount");
                }

                try (PreparedStatement credit = connection.prepareStatement(
                        "UPDATE wallets SET balance = balance + ? WHERE user_id = ?")) {
                    credit.setBigDecimal(1, amount);
                    credit.setInt(2, userId);
                    credit.executeUpdate();
                }
                try (PreparedStatement mark = connection.prepareStatement(
                        "UPDATE payments SET status = 'REFUNDED' WHERE booking_id = ?")) {
                    mark.setLong(1, bookingId);
                    mark.executeUpdate();
                }
                connection.commit();
                log.info("[gid={}] COMPENSATION refunded {} to user {} for booking {}",
                        gid, amount, userId, bookingId);
            } catch (SQLException e) {
                connection.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("refund failed for booking " + bookingId, e);
        }
    }

    /** Saga version of charge: commits immediately, on its own. */
    public void chargeNow(String gid, long bookingId, int userId, BigDecimal amount) {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                charge(connection, gid, bookingId, userId, amount);
                connection.commit();
            } catch (SQLException e) {
                connection.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("charge failed: " + e.getMessage(), e);
        }
    }
}
