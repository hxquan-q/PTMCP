package local.ptmcp.datasource.metadata;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import local.ptmcp.common.error.DomainException;
import local.ptmcp.common.error.ErrorCode;
import local.ptmcp.persistence.schema.SnapshotModel;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Real database boundary for both metadata collectors; identifiers and comments stay data. */
class MetadataCollectorTest {
    private static final String PG_IMAGE =
            System.getProperty(
                    "ptmcp.test.pgImage",
                    "postgres@sha256:edb0c08dc33befd0262ab3b5cf1c4f3f8cfd8b09e88c05b45adee29d6761ddb6");

    private static MySQLContainer mysql;
    private static PostgreSQLContainer postgres;
    private static SnapshotModel mysqlModel;
    private static Map<String, SnapshotModel.ObjectModel> mysqlObjects;
    private static SnapshotModel pgModel;
    private static Map<String, SnapshotModel.ObjectModel> pgObjects;

    @BeforeAll
    static void captureBothEngines() throws Exception {
        startMysqlAndCapture();
        startPostgresAndCapture();
    }

    @AfterAll
    static void stopContainers() {
        if (mysql != null) {
            mysql.stop();
        }
        if (postgres != null) {
            postgres.stop();
        }
    }

    private static void startMysqlAndCapture() throws Exception {
        byte[] secret = new byte[32];
        new SecureRandom().nextBytes(secret);
        String password = Base64.getUrlEncoder().withoutPadding().encodeToString(secret);
        mysql =
                new MySQLContainer(
                                System.getProperty(
                                        "ptmcp.test.mysqlImage",
                                        "mysql@sha256:76f0358884cb92d9a086d24e5671c38048d3fef40f2588703d73eb55392d6b69"))
                        .withDatabaseName("metadata_test")
                        .withUsername("metadata_test")
                        .withPassword(password)
                        .withEnv("TZ", "UTC")
                        .withCommand(
                                "--default-time-zone=+00:00",
                                "--character-set-server=utf8mb4",
                                "--collation-server=utf8mb4_0900_bin")
                        .withUrlParam("connectionTimeZone", "UTC")
                        .withUrlParam("forceConnectionTimeZoneToSession", "true")
                        .withUrlParam("allowPublicKeyRetrieval", "true")
                        .withUrlParam("useSSL", "false");
        mysql.start();
        try (Connection connection =
                        connect(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword());
                Statement statement = connection.createStatement()) {
            statement.execute(
                    "CREATE TABLE alpha (id BIGINT PRIMARY KEY, label VARCHAR(64) NULL, "
                            + "amount DECIMAL(12,2) NOT NULL) COMMENT='table comment'");
            statement.execute(
                    "ALTER TABLE alpha MODIFY label VARCHAR(64) NULL COMMENT 'label comment'");
            statement.execute(
                    "CREATE TABLE `order` (`select` BIGINT PRIMARY KEY, alpha_id BIGINT NULL, "
                            + "CONSTRAINT fk_alpha FOREIGN KEY (alpha_id) REFERENCES alpha (id))");
            statement.execute("CREATE VIEW v_alpha AS SELECT id, label FROM alpha");
            statement.execute("CREATE TABLE MixedCase (MixedCol INT PRIMARY KEY)");
        } catch (Exception | AssertionError failure) {
            mysql.stop();
            throw failure;
        }
        // The container user only owns metadata_test; root provisions the empty capture target.
        try (Connection connection = connect(mysql.getJdbcUrl(), "root", password);
                Statement statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE IF NOT EXISTS metadata_empty");
            statement.execute("GRANT ALL ON metadata_empty.* TO 'metadata_test'@'%'");
        } catch (Exception | AssertionError failure) {
            mysql.stop();
            throw failure;
        }
        mysqlModel = new MysqlMetadataCollector().collect(openMysql("metadata_test"));
        mysqlObjects = index(mysqlModel.namespaces().getFirst().objects());
    }

    /** The WSL port-forward occasionally refuses the first connections; retry instead of skip. */
    private static Connection connect(String url, String user, String password)
            throws SQLException {
        SQLException lastFailure = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                return DriverManager.getConnection(url, user, password);
            } catch (SQLException failure) {
                lastFailure = failure;
                try {
                    Thread.sleep(2000);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw failure;
                }
            }
        }
        throw lastFailure;
    }

    private static Connection openMysql(String database) throws SQLException {
        return connect(
                mysql.getJdbcUrl().replace("/metadata_test", "/" + database),
                mysql.getUsername(),
                mysql.getPassword());
    }

    private static void startPostgresAndCapture() throws Exception {
        byte[] secret = new byte[32];
        new SecureRandom().nextBytes(secret);
        postgres =
                new PostgreSQLContainer(DockerImageName.parse(PG_IMAGE))
                        .withDatabaseName("ptmcp_pg_meta")
                        .withUsername("ptmcp_test")
                        .withPassword(
                                Base64.getUrlEncoder().withoutPadding().encodeToString(secret))
                        .withEnv("TZ", "UTC")
                        .withUrlParam("sslmode", "disable");
        postgres.start();
        try (Connection connection =
                        connect(
                                postgres.getJdbcUrl(),
                                postgres.getUsername(),
                                postgres.getPassword());
                Statement statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA sales");
            statement.execute("CREATE DOMAIN sales.posint AS integer CHECK (VALUE > 0)");
            statement.execute(
                    "CREATE TABLE sales.orders (id int8 PRIMARY KEY, amount numeric(12,2), "
                            + "title varchar(50), created timestamptz(3), qty sales.posint)");
            statement.execute("COMMENT ON TABLE sales.orders IS 'orders table'");
            statement.execute("COMMENT ON COLUMN sales.orders.title IS 'title comment'");
            statement.execute("CREATE TABLE sales.\"MixedCase\" (c int)");
            statement.execute(
                    "CREATE TABLE sales.order_items (item_id int8 PRIMARY KEY, "
                            + "order_id int8 REFERENCES sales.orders (id))");
            statement.execute(
                    "CREATE VIEW sales.orders_view AS SELECT id, amount FROM sales.orders");
        } catch (Exception | AssertionError failure) {
            if (mysql != null) {
                mysql.stop();
            }
            postgres.stop();
            throw failure;
        }
        pgModel = new PgMetadataCollector().collect(openPostgres());
        pgObjects =
                index(
                        pgModel.namespaces().stream()
                                .flatMap(namespace -> namespace.objects().stream())
                                .toList());
    }

    private static Connection openPostgres() throws SQLException {
        return connect(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    }

    private static Map<String, SnapshotModel.ObjectModel> index(
            List<SnapshotModel.ObjectModel> objects) {
        return objects.stream()
                .collect(
                        Collectors.toMap(
                                SnapshotModel.ObjectModel::objectRaw, Function.identity()));
    }

    @Test
    void mysqlCaptureKeepsDatabaseNamespaceAndIdentifierFolding() {
        assertEquals(mysql.getDatabaseName(), mysqlModel.database());
        assertEquals("FALSE", mysqlModel.lowerCaseIdentifiers());
        assertEquals(1, mysqlModel.namespaces().size());
        SnapshotModel.NamespaceModel namespace = mysqlModel.namespaces().getFirst();
        assertEquals(mysql.getDatabaseName(), namespace.catalogRaw());
        assertEquals(mysql.getDatabaseName(), namespace.catalogNorm());
        assertEquals("", namespace.schemaRaw());
        assertEquals(4, namespace.objects().size());
    }

    @Test
    void mysqlCaptureProjectsColumnsKeysCommentsAndTypes() {
        SnapshotModel.ObjectModel alpha = mysqlObjects.get("alpha");
        assertNotNull(alpha);
        assertFalse(alpha.quoted());
        assertEquals(SnapshotModel.ObjectModel.KIND_TABLE, alpha.kind());
        assertEquals("table comment", alpha.tableComment());
        Map<String, SnapshotModel.ColumnModel> columns = columnIndex(alpha);
        SnapshotModel.ColumnModel id = columns.get("id");
        assertEquals(Types.BIGINT, id.jdbcType());
        assertTrue(id.primaryKey());
        assertFalse(id.nullable());
        SnapshotModel.ColumnModel label = columns.get("label");
        assertEquals(64L, label.maxLength());
        assertEquals("label comment", label.comment());
        assertTrue(label.nullable());
        SnapshotModel.ColumnModel amount = columns.get("amount");
        assertEquals(Types.DECIMAL, amount.jdbcType());
        assertEquals(12, amount.precision());
        assertEquals(2, amount.scale());
        assertFalse(amount.nullable());
    }

    @Test
    void mysqlCaptureQuotesReservedAndMixedCaseIdentifiers() {
        SnapshotModel.ObjectModel reserved = mysqlObjects.get("order");
        assertNotNull(reserved);
        assertTrue(reserved.quoted());
        SnapshotModel.ColumnModel selectColumn = columnIndex(reserved).get("select");
        assertNotNull(selectColumn);
        assertTrue(selectColumn.quoted());
        assertTrue(selectColumn.primaryKey());
        SnapshotModel.ObjectModel mixed = mysqlObjects.get("MixedCase");
        assertNotNull(mixed);
        // lctn=0 allows unquoted mixed case; only reserved words and unsafe chars need quoting.
        assertFalse(mixed.quoted());
        assertEquals("MixedCase", mixed.objectNorm());
    }

    @Test
    void mysqlCaptureAttachesForeignKeysAndViews() {
        SnapshotModel.ObjectModel reserved = mysqlObjects.get("order");
        SnapshotModel.ColumnModel alphaId = columnIndex(reserved).get("alpha_id");
        assertEquals(
                "[{\"referencedTable\":\"alpha\",\"referencedColumn\":\"id\"}]",
                alphaId.foreignKeyJson());
        SnapshotModel.ObjectModel view = mysqlObjects.get("v_alpha");
        assertEquals(SnapshotModel.ObjectModel.KIND_VIEW, view.kind());
        assertFalse(java.util.Arrays.equals(view.definitionHash(), alphaHash()));
    }

    private byte[] alphaHash() {
        return mysqlObjects.get("alpha").definitionHash();
    }

    @Test
    void mysqlEmptySchemaIsRejectedAsMetadataTooLarge() {
        DomainException error =
                assertThrows(
                        DomainException.class,
                        () -> new MysqlMetadataCollector().collect(openMysql("metadata_empty")));
        assertEquals(ErrorCode.METADATA_TOO_LARGE, error.errorCode());
    }

    @Test
    void pgCaptureGroupsUserSchemasAndExcludesSystemSchemas() {
        assertEquals(postgres.getDatabaseName(), pgModel.database());
        List<String> schemas =
                pgModel.namespaces().stream().map(SnapshotModel.NamespaceModel::schemaRaw).toList();
        assertTrue(schemas.contains("sales"));
        assertFalse(schemas.contains("information_schema"));
        assertTrue(schemas.stream().noneMatch(name -> name.startsWith("pg_")));
        for (SnapshotModel.NamespaceModel namespace : pgModel.namespaces()) {
            assertEquals(namespace.schemaRaw(), namespace.schemaNorm());
            assertEquals("", namespace.catalogRaw());
        }
    }

    @Test
    void pgCaptureProjectsTypesBoundsAndComments() {
        SnapshotModel.ObjectModel orders = pgObjects.get("orders");
        assertNotNull(orders);
        assertFalse(orders.quoted());
        assertEquals(SnapshotModel.ObjectModel.KIND_TABLE, orders.kind());
        assertNotNull(orders.physicalIdentity());
        assertTrue(orders.physicalIdentity().matches("\\d+"));
        assertEquals("orders table", orders.tableComment());
        Map<String, SnapshotModel.ColumnModel> columns = columnIndex(orders);
        SnapshotModel.ColumnModel id = columns.get("id");
        assertEquals("int8", id.dataType());
        assertEquals(Types.BIGINT, id.jdbcType());
        assertTrue(id.primaryKey());
        assertFalse(id.nullable());
        SnapshotModel.ColumnModel amount = columns.get("amount");
        assertEquals("numeric", amount.dataType());
        assertEquals(Types.DECIMAL, amount.jdbcType());
        assertEquals(12, amount.precision());
        assertEquals(2, amount.scale());
        SnapshotModel.ColumnModel title = columns.get("title");
        assertEquals("varchar", title.dataType());
        assertEquals(50L, title.maxLength());
        assertEquals("title comment", title.comment());
        assertTrue(title.nullable());
        SnapshotModel.ColumnModel created = columns.get("created");
        assertEquals(Types.TIMESTAMP_WITH_TIMEZONE, created.jdbcType());
        assertEquals(3, created.precision());
        SnapshotModel.ColumnModel qty = columns.get("qty");
        assertEquals("posint", qty.dataType());
        assertEquals(Types.OTHER, qty.jdbcType());
    }

    @Test
    void pgCaptureQuotesCaseSensitiveNamesAndKeepsForeignKeys() {
        SnapshotModel.ObjectModel mixed = pgObjects.get("MixedCase");
        assertNotNull(mixed);
        assertTrue(mixed.quoted());
        SnapshotModel.ObjectModel items = pgObjects.get("order_items");
        assertNotNull(items);
        SnapshotModel.ColumnModel orderId = columnIndex(items).get("order_id");
        assertEquals("[{\"referencedTable\":\"sales.orders.id\"}]", orderId.foreignKeyJson());
    }

    @Test
    void pgCaptureMarksViewsAndUsesRelationOidAsIdentity() {
        SnapshotModel.ObjectModel view = pgObjects.get("orders_view");
        assertEquals(SnapshotModel.ObjectModel.KIND_VIEW, view.kind());
        assertNotEqualsBytes(pgObjects.get("orders").definitionHash(), view.definitionHash());
    }

    private static void assertNotEqualsBytes(byte[] first, byte[] second) {
        assertFalse(java.util.Arrays.equals(first, second));
    }

    private static Map<String, SnapshotModel.ColumnModel> columnIndex(
            SnapshotModel.ObjectModel object) {
        return object.columns().stream()
                .collect(
                        Collectors.toMap(
                                SnapshotModel.ColumnModel::columnRaw, Function.identity()));
    }
}
