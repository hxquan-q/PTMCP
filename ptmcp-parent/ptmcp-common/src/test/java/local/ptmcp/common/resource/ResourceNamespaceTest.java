package local.ptmcp.common.resource;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Set;

class ResourceNamespaceTest {
    private static final ResourceId DATASOURCE_ID = new ResourceId(1);

    @Test
    void supportsOnlyTheTwoContractedDatabaseEngines() {
        assertEquals(
                Set.of(DatabaseEngine.MYSQL, DatabaseEngine.POSTGRESQL),
                Set.of(DatabaseEngine.values()));
    }

    @Test
    void representsMysqlDatabaseWithAnEmptySchema() {
        ResourceNamespace namespace =
                new ResourceNamespace(DATASOURCE_ID, DatabaseEngine.MYSQL, "erp", "");

        assertEquals(DATASOURCE_ID, namespace.datasourceId());
        assertEquals(DatabaseEngine.MYSQL, namespace.engine());
        assertEquals("erp", namespace.catalog());
        assertEquals("", namespace.schema());
    }

    @Test
    void representsPostgresqlDatabaseAndExplicitSchema() {
        ResourceNamespace namespace =
                new ResourceNamespace(DATASOURCE_ID, DatabaseEngine.POSTGRESQL, "erp", "sales");

        assertEquals(DATASOURCE_ID, namespace.datasourceId());
        assertEquals(DatabaseEngine.POSTGRESQL, namespace.engine());
        assertEquals("erp", namespace.catalog());
        assertEquals("sales", namespace.schema());
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"public", " ", "\0", "\uD800"})
    void mysqlRequiresExactlyTheEmptyStringForSchema(String schema) {
        assertThrows(
                IllegalArgumentException.class,
                () -> new ResourceNamespace(DATASOURCE_ID, DatabaseEngine.MYSQL, "erp", schema));
    }

    @Test
    void rejectsMissingDatasourceAndEngine() {
        assertAll(
                () ->
                        assertThrows(
                                NullPointerException.class,
                                () -> new ResourceNamespace(null, DatabaseEngine.MYSQL, "erp", "")),
                () ->
                        assertThrows(
                                NullPointerException.class,
                                () -> new ResourceNamespace(DATASOURCE_ID, null, "erp", "")));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"\0", "a\0b", "\uD800", "a\uD800b", "\uDC00", "\uDC00\uD800"})
    void rejectsInvalidNamesInEveryIdentifierPosition(String name) {
        assertAll(
                () ->
                        assertThrows(
                                IllegalArgumentException.class,
                                () ->
                                        new ResourceNamespace(
                                                DATASOURCE_ID, DatabaseEngine.MYSQL, name, "")),
                () ->
                        assertThrows(
                                IllegalArgumentException.class,
                                () ->
                                        new ResourceNamespace(
                                                DATASOURCE_ID,
                                                DatabaseEngine.POSTGRESQL,
                                                name,
                                                "sales")),
                () ->
                        assertThrows(
                                IllegalArgumentException.class,
                                () ->
                                        new ResourceNamespace(
                                                DATASOURCE_ID,
                                                DatabaseEngine.POSTGRESQL,
                                                "erp",
                                                name)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"a", "\uD83D\uDE80"})
    void enforcesThe128CodepointLimitNotTheUtf16Length(String character) {
        String maximum = character.repeat(128);
        String tooLong = character.repeat(129);

        assertEquals(
                maximum,
                new ResourceNamespace(DATASOURCE_ID, DatabaseEngine.MYSQL, maximum, "").catalog());
        assertEquals(
                maximum,
                new ResourceNamespace(DATASOURCE_ID, DatabaseEngine.POSTGRESQL, maximum, maximum)
                        .schema());
        assertAll(
                () ->
                        assertThrows(
                                IllegalArgumentException.class,
                                () ->
                                        new ResourceNamespace(
                                                DATASOURCE_ID, DatabaseEngine.MYSQL, tooLong, "")),
                () ->
                        assertThrows(
                                IllegalArgumentException.class,
                                () ->
                                        new ResourceNamespace(
                                                DATASOURCE_ID,
                                                DatabaseEngine.POSTGRESQL,
                                                tooLong,
                                                "sales")),
                () ->
                        assertThrows(
                                IllegalArgumentException.class,
                                () ->
                                        new ResourceNamespace(
                                                DATASOURCE_ID,
                                                DatabaseEngine.POSTGRESQL,
                                                "erp",
                                                tooLong)));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                " Orders ",
                " ",
                "sales.orders",
                "select",
                "a\"b",
                "a`b",
                "O'Reilly",
                "é",
                "e\u0301",
                "表\uD83D\uDE80",
                "table; DROP TABLE x --",
                "\t"
            })
    void preservesSnapshotNamesWithoutSqlIdentifierRulesOrNormalization(String name) {
        ResourceNamespace mysql =
                new ResourceNamespace(DATASOURCE_ID, DatabaseEngine.MYSQL, name, "");
        ResourceNamespace postgresql =
                new ResourceNamespace(DATASOURCE_ID, DatabaseEngine.POSTGRESQL, name, name);

        assertEquals(name, mysql.catalog());
        assertEquals(name, postgresql.catalog());
        assertEquals(name, postgresql.schema());
    }

    @ParameterizedTest
    @EnumSource(DatabaseEngine.class)
    void diagnosticsOmitSnapshotNamesWithoutChangingAccessors(DatabaseEngine engine) {
        String catalog = "SYNTHETIC_PRIVATE_CATALOG_62fa";
        String schema = engine == DatabaseEngine.MYSQL ? "" : "SYNTHETIC_PRIVATE_SCHEMA_62fa";
        ResourceNamespace namespace = new ResourceNamespace(DATASOURCE_ID, engine, catalog, schema);

        assertAll(
                () -> assertEquals(catalog, namespace.catalog()),
                () -> assertEquals(schema, namespace.schema()),
                () ->
                        assertEquals(
                                "ResourceNamespace[datasourceId=1, engine=" + engine + "]",
                                namespace.toString()));
    }

    @Test
    void invalidNameErrorsDoNotEchoSnapshotData() {
        IllegalArgumentException catalogError =
                assertThrows(
                        IllegalArgumentException.class,
                        () ->
                                new ResourceNamespace(
                                        DATASOURCE_ID,
                                        DatabaseEngine.MYSQL,
                                        "SYNTHETIC_PRIVATE_CATALOG_62fa\0",
                                        ""));
        IllegalArgumentException schemaError =
                assertThrows(
                        IllegalArgumentException.class,
                        () ->
                                new ResourceNamespace(
                                        DATASOURCE_ID,
                                        DatabaseEngine.POSTGRESQL,
                                        "erp",
                                        "SYNTHETIC_PRIVATE_SCHEMA_62fa\0"));
        IllegalArgumentException mysqlSchemaError =
                assertThrows(
                        IllegalArgumentException.class,
                        () ->
                                new ResourceNamespace(
                                        DATASOURCE_ID,
                                        DatabaseEngine.MYSQL,
                                        "erp",
                                        "SYNTHETIC_PRIVATE_SCHEMA_62fa"));

        assertEquals(
                "catalog must be 1 to 128 Unicode codepoints without NUL or malformed UTF-16",
                catalogError.getMessage());
        assertEquals(
                "schema must be 1 to 128 Unicode codepoints without NUL or malformed UTF-16",
                schemaError.getMessage());
        assertEquals("MySQL schema must be the empty string", mysqlSchemaError.getMessage());
    }
}
