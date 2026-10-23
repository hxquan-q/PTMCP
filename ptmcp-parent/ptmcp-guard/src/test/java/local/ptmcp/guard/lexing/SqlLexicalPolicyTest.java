package local.ptmcp.guard.lexing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import local.ptmcp.common.resource.DatabaseEngine;

import org.junit.jupiter.api.Test;

class SqlLexicalPolicyTest {
    @Test
    void mysqlModesAreExplicitAndDefaultBudgetIsOnlyAHardUpperBound() {
        for (boolean ansi : new boolean[] {false, true}) {
            for (boolean noBackslash : new boolean[] {false, true}) {
                SqlLexicalPolicy policy = SqlLexicalPolicy.mysql(ansi, noBackslash);
                assertEquals(DatabaseEngine.MYSQL, policy.engine());
                assertEquals(ansi, policy.ansiQuotes());
                assertEquals(noBackslash, policy.noBackslashEscapes());
                assertEquals(65536, policy.maxSqlBytes());
                assertEquals(1, SqlLexicalPolicy.mysql(ansi, noBackslash, 1).maxSqlBytes());
            }
        }
    }

    @Test
    void postgresModeIsExplicitAndFactoryHasNoMySqlModeSwitches() {
        assertEquals(DatabaseEngine.POSTGRESQL, SqlLexicalPolicy.postgresql(true).engine());
        assertTrue(SqlLexicalPolicy.postgresql(true).standardConformingStrings());
        assertFalse(SqlLexicalPolicy.postgresql(false).standardConformingStrings());
        assertEquals(65536, SqlLexicalPolicy.postgresql(false).maxSqlBytes());
        assertEquals(1, SqlLexicalPolicy.postgresql(true, 1).maxSqlBytes());
    }
}
