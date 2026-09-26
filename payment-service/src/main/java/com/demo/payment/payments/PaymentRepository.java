package com.demo.payment.payments;

import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;

/** All the SQL for payments. No policy, no transactions. */
@Repository
public class PaymentRepository {

    /**
     * Claims the booking id. INSERT IGNORE returns 0 when the row already
     * exists, which is how a replayed step is told apart from a new one.
     *
     * @return true if this call is the one that claimed it
     */
    public boolean claim(Connection connection, String transactionId, long bookingId,
                         int userId, BigDecimal amount) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT IGNORE INTO payments (booking_id, transaction_id, user_id, amount, status) "
                + "VALUES (?, ?, ?, ?, 'CHARGED')")) {
            insert.setLong(1, bookingId);
            insert.setString(2, transactionId);
            insert.setInt(3, userId);
            insert.setBigDecimal(4, amount);
            return insert.executeUpdate() == 1;
        }
    }

    /** Fails on the CHECK constraint when the wallet would go negative. */
    public void debit(Connection connection, int userId, BigDecimal amount) throws SQLException {
        move(connection, userId, amount.negate());
    }

    public void credit(Connection connection, int userId, BigDecimal amount) throws SQLException {
        move(connection, userId, amount);
    }

    public Optional<Payment> findForUpdate(Connection connection, long bookingId) throws SQLException {
        try (PreparedStatement find = connection.prepareStatement(
                "SELECT user_id, amount, status FROM payments WHERE booking_id = ? FOR UPDATE")) {
            find.setLong(1, bookingId);
            try (ResultSet row = find.executeQuery()) {
                if (!row.next()) {
                    return Optional.empty();
                }
                return Optional.of(new Payment(
                        row.getInt("user_id"), row.getBigDecimal("amount"), row.getString("status")));
            }
        }
    }

    public void markRefunded(Connection connection, long bookingId) throws SQLException {
        try (PreparedStatement mark = connection.prepareStatement(
                "UPDATE payments SET status = 'REFUNDED' WHERE booking_id = ?")) {
            mark.setLong(1, bookingId);
            mark.executeUpdate();
        }
    }

    private void move(Connection connection, int userId, BigDecimal delta) throws SQLException {
        try (PreparedStatement update = connection.prepareStatement(
                "UPDATE wallets SET balance = balance + ? WHERE user_id = ?")) {
            update.setBigDecimal(1, delta);
            update.setInt(2, userId);
            update.executeUpdate();
        }
    }

    public record Payment(int userId, BigDecimal amount, String status) {
    }
}
