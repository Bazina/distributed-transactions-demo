package com.demo.payment.payments;

import com.demo.payment.db.Database;
import com.demo.payment.util.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;

/** One row and one log line per step. There is no business logic in the demo. */
@Service
public class PaymentService {
    private static final Logger logger = LoggerFactory.getLogger(PaymentService.class);

    private final Database database;
    private final PaymentRepository payments;

    public PaymentService(Database database, PaymentRepository payments) {
        this.database = database;
        this.payments = payments;
    }

    /**
     * Takes the money on a connection somebody else owns, so it can be part of
     * a two-phase commit branch. The unique key is claimed before the money
     * moves, otherwise a replay keeps one row and charges twice.
     *
     * @param connection the connection carrying the XA branch
     */
    public void charge(Connection connection, String transactionId, long bookingId,
                       int userId, BigDecimal amount) throws SQLException {
        if (!payments.claim(connection, transactionId, bookingId, userId, amount)) {
            logger.info("{} booking {} was already charged, no-op", transactionId, bookingId);
            return;
        }
        payments.debit(connection, userId, amount);
        logger.info("{} charged {} from user {} for booking {}", transactionId, amount, userId, bookingId);
    }

    /** The saga version. Commits on its own, so the money is really gone. */
    public void chargeNow(String transactionId, long bookingId, int userId, BigDecimal amount) {
        Timer timer = new Timer();
        database.transaction(connection -> {
            charge(connection, transactionId, bookingId, userId, amount);
            return null;
        });
        logger.info("{} charge committed in {}ms", transactionId, timer.elapsedMillis());
    }

    /**
     * Gives the money back. A compensation, not an undo: the payment row stays
     * and changes status, so the history survives.
     */
    public void refund(String transactionId, long bookingId) {
        Timer timer = new Timer();
        database.transaction(connection -> {
            var payment = payments.findForUpdate(connection, bookingId);
            if (payment.isEmpty()) {
                logger.info("{} refund for booking {} had nothing to undo", transactionId, bookingId);
                return null;
            }
            if ("REFUNDED".equals(payment.get().status())) {
                logger.info("{} booking {} was already refunded, no-op", transactionId, bookingId);
                return null;
            }
            payments.credit(connection, payment.get().userId(), payment.get().amount());
            payments.markRefunded(connection, bookingId);
            logger.info("{} refunded {} to user {} for booking {} in {}ms",
                    transactionId, payment.get().amount(), payment.get().userId(),
                    bookingId, timer.elapsedMillis());
            return null;
        });
    }
}
