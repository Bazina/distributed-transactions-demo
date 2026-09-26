package com.demo.booking.db;

import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;

/** Runs a unit of work in one transaction, so nothing else has to repeat this. */
@Component
public class Database {
    private final DataSource dataSource;

    public Database(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    public interface Work<T> {
        T run(Connection connection) throws SQLException;
    }

    public <T> T transaction(Work<T> work) {
        return transaction(Connection.TRANSACTION_REPEATABLE_READ, work);
    }

    public <T> T transaction(int isolation, Work<T> work) {
        try (Connection connection = dataSource.getConnection()) {
            connection.setTransactionIsolation(isolation);
            connection.setAutoCommit(false);
            try {
                T value = work.run(connection);
                connection.commit();
                return value;
            } catch (Exception e) {
                connection.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new DatabaseException(e.getMessage(), e);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new DatabaseException(e.getMessage(), e);
        }
    }
}
