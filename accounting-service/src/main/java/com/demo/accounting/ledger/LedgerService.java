package com.demo.accounting.ledger;

import com.demo.accounting.db.Database;
import com.demo.accounting.util.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;

/**
 * You cannot un-write an accounting entry. A compensation writes the opposite
 * one and both stay in the history.
 */
@Service
public class LedgerService {
    private static final Logger logger = LoggerFactory.getLogger(LedgerService.class);

    private final Database database;
    private final LedgerRepository ledger;

    public LedgerService(Database database, LedgerRepository ledger) {
        this.database = database;
        this.ledger = ledger;
    }

    /**
     * @param connection the connection carrying the XA branch
     */
    public void post(Connection connection, String transactionId, long bookingId,
                     String kind, BigDecimal amount) throws SQLException {
        if (ledger.post(connection, transactionId, bookingId, kind, amount)) {
            logger.info("{} ledger {} {} for booking {}", transactionId, kind, amount, bookingId);
        } else {
            logger.info("{} ledger {} for booking {} already exists, no-op", transactionId, kind, bookingId);
        }
    }

    /** The saga version. Commits on its own. */
    public void postNow(String transactionId, long bookingId, String kind, BigDecimal amount) {
        Timer timer = new Timer();
        database.transaction(connection -> {
            post(connection, transactionId, bookingId, kind, amount);
            return null;
        });
        logger.info("{} ledger {} committed in {}ms", transactionId, kind, timer.elapsedMillis());
    }
}
