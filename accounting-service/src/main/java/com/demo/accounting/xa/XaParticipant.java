package com.demo.accounting.xa;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * A 2PC participant, built on MySQL's own XA statements.
 *
 * The important property is that after XA PREPARE the transaction no longer
 * belongs to the connection that created it. It is durable, it still holds its
 * row locks, and any other session can finish it. That is what lets us kill the
 * coordinator, kill this service, restart MySQL, and still find the transaction
 * sitting in XA RECOVER waiting for a decision it cannot make on its own.
 *
 * DDIA p.357: by replying yes, the node surrenders the right to abort.
 */
@Component
public class XaParticipant {

    private static final Logger log = LoggerFactory.getLogger(XaParticipant.class);

    /** Global transaction ids are embedded in SQL, so keep them boring. */
    private static final Pattern SAFE_GID = Pattern.compile("[A-Za-z0-9._:-]{1,60}");

    private final DataSource dataSource;

    /**
     * This participant's branch qualifier, the second half of the XID.
     *
     * An XID is (gtrid, bqual): one global transaction, one branch per
     * participant. All three of our databases live on the same MySQL server and
     * XIDs are global to the server, so without a distinct bqual the second
     * participant to prepare gets XAER_DUPID and votes no.
     */
    private final String branch;

    public XaParticipant(DataSource dataSource, @Value("${demo.xa-branch}") String branch) {
        this.dataSource = dataSource;
        this.branch = branch;
    }

    /** The work a participant does between XA START and XA END. */
    public interface Work {
        void run(Connection connection) throws SQLException;
    }

    /**
     * Phase one. Runs the work inside an XA branch and prepares it.
     *
     * @return true for a "yes" vote, false for "no"
     */
    public boolean prepare(String gid, Work work) {
        String id = checked(gid);
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {

            statement.execute("XA START '" + id + "','" + branch + "'");
            try {
                work.run(connection);
            } catch (SQLException workFailed) {
                statement.execute("XA END '" + id + "','" + branch + "'");
                statement.execute("XA ROLLBACK '" + id + "','" + branch + "'");
                log.info("[gid={}] PREPARE -> NO ({})", id, workFailed.getMessage());
                return false;
            }
            statement.execute("XA END '" + id + "','" + branch + "'");
            statement.execute("XA PREPARE '" + id + "','" + branch + "'");

            log.info("[gid={}] branch '{}' PREPARE -> YES, locks are held until a decision arrives", id, branch);
            return true;

        } catch (SQLException e) {
            log.warn("[gid={}] PREPARE -> NO ({})", id, e.getMessage());
            return false;
        }
    }

    /** Phase two, commit. Safe to call from any connection, and safe to retry. */
    public void commit(String gid) {
        finish(gid, "XA COMMIT", "COMMIT");
    }

    /** Phase two, abort. Safe to call from any connection, and safe to retry. */
    public void rollback(String gid) {
        finish(gid, "XA ROLLBACK", "ABORT");
    }

    private void finish(String gid, String sql, String label) {
        String id = checked(gid);
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute(sql + " '" + id + "','" + branch + "'");
            log.info("[gid={}] {} done, locks released", id, label);
        } catch (SQLException e) {
            // XAER_NOTA means it is already finished. Replaying a decision is
            // not an error, the coordinator is allowed to retry forever.
            if (e.getMessage() != null && e.getMessage().contains("XAER_NOTA")) {
                log.info("[gid={}] {} was already applied, nothing to do", id, label);
                return;
            }
            throw new IllegalStateException(label + " failed for " + id, e);
        }
    }

    /**
     * The global transactions this participant is in doubt about.
     *
     * XA RECOVER is scoped to the MySQL server, not to a schema, and all three
     * of our databases share one server. So we split each XID back into its two
     * halves using the lengths MySQL reports, and keep only our own branch.
     * On three separate servers this filter would be unnecessary.
     */
    public List<String> inDoubt() {
        List<String> ids = new ArrayList<>();
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("XA RECOVER")) {
            while (rows.next()) {
                String xid = rows.getString("data");
                int gtridLength = rows.getInt("gtrid_length");
                String gtrid = xid.substring(0, gtridLength);
                String bqual = xid.substring(gtridLength);
                if (branch.equals(bqual)) {
                    ids.add(gtrid);
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("XA RECOVER failed", e);
        }
        return ids;
    }

    private static String checked(String gid) {
        if (gid == null || !SAFE_GID.matcher(gid).matches()) {
            throw new IllegalArgumentException("bad global transaction id: " + gid);
        }
        return gid;
    }
}
