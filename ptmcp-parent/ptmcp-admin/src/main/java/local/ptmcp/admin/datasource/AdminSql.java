package local.ptmcp.admin.datasource;

import local.ptmcp.common.error.DomainException;
import local.ptmcp.common.error.ErrorCode;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;

import javax.sql.DataSource;

/** JDBC plumbing shared by the datasource/job/catalog admin stores. */
final class AdminSql {
    static final int STATEMENT_SECONDS = 5;

    private AdminSql() {}

    static Connection configure(DataSource dataSource) throws SQLException {
        Connection connection = dataSource.getConnection();
        if (!connection.getAutoCommit()) {
            connection.close();
            throw new DomainException(ErrorCode.INTERNAL_ERROR);
        }
        connection.setReadOnly(false);
        connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
        connection.setNetworkTimeout(Runnable::run, 20_000);
        return connection;
    }

    static PreparedStatement statement(Connection connection, String sql) throws SQLException {
        PreparedStatement statement = connection.prepareStatement(sql);
        statement.setQueryTimeout(STATEMENT_SECONDS);
        return statement;
    }

    static PreparedStatement generatedKeys(Connection connection, String sql) throws SQLException {
        PreparedStatement statement =
                connection.prepareStatement(sql, PreparedStatement.RETURN_GENERATED_KEYS);
        statement.setQueryTimeout(STATEMENT_SECONDS);
        return statement;
    }

    static LocalDateTime nowUtc() {
        return LocalDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
    }

    static LocalDateTime ofInstant(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
    }

    static Instant toInstant(LocalDateTime value) {
        return value == null ? null : value.toInstant(ZoneOffset.UTC);
    }

    static void requireActor(long actorId) {
        if (actorId <= 0) {
            throw new DomainException(ErrorCode.AUTHENTICATION_REQUIRED);
        }
    }

    static void requireText(String value, int maxLength) {
        if (value == null || value.isBlank() || value.length() > maxLength) {
            throw new DomainException(ErrorCode.INVALID_ARGUMENT);
        }
    }

    static long lastId(Connection connection, PreparedStatement statement) throws SQLException {
        try (var keys = statement.getGeneratedKeys()) {
            if (!keys.next()) {
                throw new DomainException(ErrorCode.INTERNAL_ERROR);
            }
            return keys.getLong(1);
        }
    }
}
