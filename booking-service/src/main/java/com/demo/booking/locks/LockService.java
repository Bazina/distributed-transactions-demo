package com.demo.booking.locks;

import com.demo.booking.db.Database;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Optional;

/**
 * A lease that hands out fencing tokens. The counter only goes up, and an
 * expired lease does not reset it. DDIA figure 8-5.
 */
@Service
public class LockService {
    private static final Logger logger = LoggerFactory.getLogger(LockService.class);

    private static final String TAKE_LEASE =
            "UPDATE lock_lease SET owner = ?, token = token + 1, expires_at = NOW() + INTERVAL ? SECOND "
            + "WHERE lock_name = ? AND (owner IS NULL OR expires_at IS NULL OR expires_at < NOW())";

    private final Database database;

    public LockService(Database database) {
        this.database = database;
    }

    /** One statement decides the winner, so two clients cannot both take it. */
    public Optional<Long> acquire(String lockName, String owner, int ttlSeconds) {
        return database.transaction(connection -> {
            try (PreparedStatement take = connection.prepareStatement(TAKE_LEASE)) {
                take.setString(1, owner);
                take.setInt(2, ttlSeconds);
                take.setString(3, lockName);
                if (take.executeUpdate() == 0) {
                    logger.info("{} refused to {}, still held", lockName, owner);
                    return Optional.empty();
                }
            }
            try (PreparedStatement read = connection.prepareStatement(
                    "SELECT token FROM lock_lease WHERE lock_name = ?")) {
                read.setString(1, lockName);
                try (ResultSet row = read.executeQuery()) {
                    row.next();
                    long token = row.getLong(1);
                    logger.info("{} granted to {} with token {} for {}s", lockName, owner, token, ttlSeconds);
                    return Optional.of(token);
                }
            }
        });
    }

    public void release(String lockName, String owner) {
        database.transaction(connection -> {
            try (PreparedStatement free = connection.prepareStatement(
                    "UPDATE lock_lease SET owner = NULL, expires_at = NULL WHERE lock_name = ? AND owner = ?")) {
                free.setString(1, lockName);
                free.setString(2, owner);
                return free.executeUpdate();
            }
        });
    }
}
