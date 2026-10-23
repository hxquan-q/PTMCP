package local.ptmcp.guard.budget;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import local.ptmcp.common.error.DomainException;
import local.ptmcp.common.error.ErrorCode;

import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.select.Select;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

class BoundedParserTest {
    @Test
    void parsesASelectWithinBudget() {
        try (BoundedParser parser = new BoundedParser(Duration.ofSeconds(2))) {
            Select select = parser.parse("SELECT 1");
            assertNotNull(select);
        }
    }

    @Test
    void mapsSyntaxErrorsToTheRegisteredCodeWithoutEchoingSql() {
        try (BoundedParser parser = new BoundedParser(Duration.ofSeconds(2))) {
            DomainException error =
                    assertThrows(
                            DomainException.class,
                            () -> parser.parse("SELECT SECRET_TOKEN FROM WHERE"));

            assertEquals(ErrorCode.SQL_SYNTAX_INVALID, error.errorCode());
            assertFalse(error.getMessage().contains("SECRET_TOKEN"));
        }
    }

    @Test
    void enforcesTheUtf8InputBudget() {
        try (BoundedParser parser = new BoundedParser(Duration.ofSeconds(2))) {
            String exact = "SELECT 1" + " ".repeat(65_536 - 8);
            assertEquals(65_536, exact.getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
            assertNotNull(parser.parse(exact));

            String tooLarge = exact + " ";
            DomainException error =
                    assertThrows(DomainException.class, () -> parser.parse(tooLarge));
            assertEquals(ErrorCode.SQL_BUDGET_EXCEEDED, error.errorCode());
        }
    }

    @Test
    void timesOutAndReplacesTheQuarantinedWorker() throws Exception {
        Select fast = (Select) CCJSqlParserUtil.parse("SELECT 1");
        CountDownLatch releaseSlow = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        String[] workerNames = new String[2];
        BoundedParser.ParserEngine engine =
                sql -> {
                    int call = calls.getAndIncrement();
                    workerNames[call] = Thread.currentThread().getName();
                    if (call == 0) {
                        releaseSlow.await(10, TimeUnit.SECONDS);
                    }
                    return fast;
                };
        try (BoundedParser parser = new BoundedParser(engine, 1, 1, Duration.ofSeconds(1), 2)) {
            DomainException error =
                    assertThrows(DomainException.class, () -> parser.parse("SELECT slow"));
            assertEquals(ErrorCode.SQL_BUDGET_EXCEEDED, error.errorCode());

            assertNotNull(parser.parse("SELECT fast"));
            assertNotEquals(workerNames[0], workerNames[1]);
        } finally {
            releaseSlow.countDown();
        }
    }

    @Test
    void parserThreadFailuresDoNotLeakToTheCaller() {
        BoundedParser.ParserEngine engine =
                sql -> {
                    throw new IllegalStateException("driver detail with secret");
                };
        try (BoundedParser parser = new BoundedParser(engine, 1, 1, Duration.ofSeconds(1), 1)) {
            DomainException error =
                    assertThrows(DomainException.class, () -> parser.parse("SELECT 1"));

            assertEquals(ErrorCode.SQL_SYNTAX_INVALID, error.errorCode());
            assertFalse(error.getMessage().contains("secret"));
        }
    }

    @Test
    void closedParserRejectsNewTasks() {
        BoundedParser parser = new BoundedParser(Duration.ofSeconds(1));
        parser.close();

        assertThrows(IllegalStateException.class, () -> parser.parse("SELECT 1"));
    }
}
