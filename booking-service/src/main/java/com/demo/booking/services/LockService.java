package com.demo.booking.services;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;

/**
 * A lease based lock that hands out fencing tokens.
 *
 * The counter only ever goes up. An expired lease does not reset it, which is
 * the property the resource relies on when it rejects an old token.
 * DDIA p.303, figure 8-5.
 */
@Service
public class LockService {

    private static final Logger log = LoggerFactory.getLogger(LockService.class);

    private final DataSource dataSource;

    public LockService(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /**
     * Take the lease if it is free or expired, and get the next token.
     *
     * One statement, so two clients racing cannot both win: the row lock on
     * lock_lease serialises them and only one UPDATE reports a changed row.
     *
     * @return the fencing token, or empty if somebody else holds the lease
     */
    public Optional<Long> acquire(String lockName, String owner, int ttlSeconds) {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                int taken;
                try (PreparedStatement grab = connection.prepareStatement(
                        "UPDATE lock_lease " +
                        "   SET owner = ?, token = token + 1, expires_at = NOW() + INTERVAL ? SECOND " +
                        " WHERE lock_name = ? " +
                        "   AND (owner IS NULL OR expires_at IS NULL OR expires_at < NOW())")) {
                    grab.setString(1, owner);
                    grab.setInt(2, ttlSeconds);
                    grab.setString(3, lockName);
                    taken = grab.executeUpdate();
                }

                if (taken == 0) {
                    connection.rollback();
                    log.info("[lock] {} refused to {}, still held", lockName, owner);
                    return Optional.empty();
                }

                long token;
                try (PreparedStatement read = connection.prepareStatement(
                        "SELECT token FROM lock_lease WHERE lock_name = ?")) {
                    read.setString(1, lockName);
                    try (ResultSet row = read.executeQuery()) {
                        row.next();
                        token = row.getLong(1);
                    }
                }
                connection.commit();
                log.info("[lock] {} granted to {} with fencing token {} for {}s",
                        lockName, owner, token, ttlSeconds);
                return Optional.of(token);
            } catch (SQLException e) {
                connection.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("acquire failed for " + lockName, e);
        }
    }

    /** Give the lease back early. The token is not returned, it is spent. */
    public void release(String lockName, String owner) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement free = connection.prepareStatement(
                     "UPDATE lock_lease SET owner = NULL, expires_at = NULL " +
                     " WHERE lock_name = ? AND owner = ?")) {
            free.setString(1, lockName);
            free.setString(2, owner);
            if (free.executeUpdate() > 0) {
                log.info("[lock] {} released by {}", lockName, owner);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("release failed for " + lockName, e);
        }
    }
}
