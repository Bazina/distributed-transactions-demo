package com.demo.booking.xa;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.demo.booking.util.Timer;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * One participant in a two-phase commit, using MySQL's XA statements.
 *
 * XA PREPARE makes the writes durable but not visible, and keeps their locks.
 * The transaction then belongs to the server rather than to this connection,
 * so any session can finish it later.
 */
@Component
public class XaParticipant {

    private static final Logger logger = LoggerFactory.getLogger(XaParticipant.class);
    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9._:-]{1,60}");

    private final DataSource dataSource;
    private final String branch;

    public XaParticipant(DataSource dataSource, @Value("${demo.xa-branch}") String branch) {
        this.dataSource = dataSource;
        this.branch = branch;
    }

    public interface Work {
        void run(Connection connection) throws SQLException;
    }

    /** Phase one. Returns the vote. */
    public boolean prepare(String transactionId, Work work) {
        String xid = xid(transactionId);
        Timer timer = new Timer();
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {

            statement.execute("XA START " + xid);
            try {
                work.run(connection);
            } catch (SQLException failed) {
                statement.execute("XA END " + xid);
                statement.execute("XA ROLLBACK " + xid);
                logger.info("{} vote NO in {}ms, {}", transactionId, timer.elapsedMillis(), failed.getMessage());
                return false;
            }
            statement.execute("XA END " + xid);
            statement.execute("XA PREPARE " + xid);

            logger.info("{} vote YES in {}ms, this branch is durable and locked",
                    transactionId, timer.elapsedMillis());
            return true;

        } catch (SQLException e) {
            logger.warn("{} vote NO in {}ms, {}", transactionId, timer.elapsedMillis(), e.getMessage());
            return false;
        }
    }

    public void commit(String transactionId) {
        finish(transactionId, "XA COMMIT");
    }

    public void rollback(String transactionId) {
        finish(transactionId, "XA ROLLBACK");
    }

    /** Phase two. Any connection can run it, and running it twice is fine. */
    private void finish(String transactionId, String command) {
        Timer timer = new Timer();
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute(command + " " + xid(transactionId));
            logger.info("{} {} in {}ms", transactionId, command, timer.elapsedMillis());
        } catch (SQLException e) {
            if (String.valueOf(e.getMessage()).contains("XAER_NOTA")) {
                return;
            }
            throw new IllegalStateException(command + " failed for " + transactionId, e);
        }
    }

    /** Transactions this branch prepared and nobody has resolved. */
    public List<String> inDoubt() {
        List<String> ids = new ArrayList<>();
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("XA RECOVER")) {
            while (rows.next()) {
                String data = rows.getString("data");
                int split = rows.getInt("gtrid_length");
                if (branch.equals(data.substring(split))) {
                    ids.add(data.substring(0, split));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("XA RECOVER failed", e);
        }
        return ids;
    }

    /**
     * An XID has two parts: the transaction id everyone shares, and a branch
     * qualifier naming this participant. Our three databases sit on one MySQL
     * server and XIDs are unique per server, so the branch is what keeps them
     * apart.
     */
    private String xid(String transactionId) {
        if (transactionId == null || !SAFE_ID.matcher(transactionId).matches()) {
            throw new IllegalArgumentException("bad transaction id: " + transactionId);
        }
        return "'" + transactionId + "','" + branch + "'";
    }
}
