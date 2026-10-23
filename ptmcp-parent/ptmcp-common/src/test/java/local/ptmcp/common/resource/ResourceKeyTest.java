package local.ptmcp.common.resource;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

class ResourceKeyTest {
    private static final ResourceNamespace NAMESPACE =
            new ResourceNamespace(new ResourceId(1), DatabaseEngine.POSTGRESQL, "erp", "sales");

    @Test
    void sameExactNamespaceAndNameProduceTheSameKey() {
        ResourceKey key = new ResourceKey(NAMESPACE, "orders");
        ResourceKey sameKey =
                new ResourceKey(
                        new ResourceNamespace(
                                new ResourceId(1), DatabaseEngine.POSTGRESQL, "erp", "sales"),
                        "orders");

        assertEquals(NAMESPACE, key.namespace());
        assertEquals("orders", key.objectName());
        assertEquals(key, sameKey);
        assertEquals(key.hashCode(), sameKey.hashCode());
        assertEquals(1, new HashSet<>(List.of(key, sameKey)).size());
    }

    @Test
    void sameNamedObjectsCannotCollapseAcrossDatasourceCatalogOrSchema() {
        ResourceKey key = new ResourceKey(NAMESPACE, "orders");
        ResourceKey otherDatasource =
                new ResourceKey(
                        new ResourceNamespace(
                                new ResourceId(2), DatabaseEngine.POSTGRESQL, "erp", "sales"),
                        "orders");
        ResourceKey otherCatalog =
                new ResourceKey(
                        new ResourceNamespace(
                                new ResourceId(1), DatabaseEngine.POSTGRESQL, "other", "sales"),
                        "orders");
        ResourceKey otherSchema =
                new ResourceKey(
                        new ResourceNamespace(
                                new ResourceId(1), DatabaseEngine.POSTGRESQL, "erp", "public"),
                        "orders");

        assertEquals(4, Set.of(key, otherDatasource, otherCatalog, otherSchema).size());
    }

    @Test
    void keysFromLegalMysqlAndPostgresqlNamespacesAreDifferent() {
        ResourceKey mysql =
                new ResourceKey(
                        new ResourceNamespace(new ResourceId(1), DatabaseEngine.MYSQL, "erp", ""),
                        "orders");

        // Both engine and schema differ: valid MySQL/PG schemas cannot be held equal.
        // Independent engine participation follows ResourceNamespace's generated record equality.
        assertNotEquals(NAMESPACE, mysql.namespace());
        assertNotEquals(new ResourceKey(NAMESPACE, "orders"), mysql);
    }

    @Test
    void dotsInsideNamesCannotMergeSeparateKeyComponents() {
        ResourceKey dottedSchema =
                new ResourceKey(
                        new ResourceNamespace(
                                new ResourceId(1),
                                DatabaseEngine.POSTGRESQL,
                                "erp",
                                "sales.archive"),
                        "orders");
        ResourceKey dottedObject = new ResourceKey(NAMESPACE, "archive.orders");

        assertNotEquals(dottedSchema, dottedObject);
    }

    @Test
    void rejectsAMissingNamespace() {
        assertThrows(NullPointerException.class, () -> new ResourceKey(null, "orders"));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"\0", "a\0b", "\uD800", "a\uD800b", "\uDC00", "\uDC00\uD800"})
    void rejectsInvalidObjectNames(String objectName) {
        assertThrows(IllegalArgumentException.class, () -> new ResourceKey(NAMESPACE, objectName));
    }

    @ParameterizedTest
    @ValueSource(strings = {"a", "\uD83D\uDE80"})
    void measuresThe128CharacterBoundaryInUnicodeCodepoints(String character) {
        String maximum = character.repeat(128);
        String tooLong = character.repeat(129);

        assertEquals(maximum, new ResourceKey(NAMESPACE, maximum).objectName());
        assertThrows(IllegalArgumentException.class, () -> new ResourceKey(NAMESPACE, tooLong));
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
    void preservesNamesThatNeedQuotingWithoutTreatingThemAsSql(String objectName) {
        assertEquals(objectName, new ResourceKey(NAMESPACE, objectName).objectName());
    }

    @ParameterizedTest
    @ValueSource(strings = {"orders", " orders", "orders ", "Orders", "é", "e\u0301"})
    void doesNotMergeCaseWhitespaceOrUnicodeVariantsInAnyNameComponent(String variant) {
        String exact = variant.equals("é") || variant.equals("e\u0301") ? "É" : "ORDERS";
        ResourceNamespace namespace =
                new ResourceNamespace(new ResourceId(1), DatabaseEngine.POSTGRESQL, exact, exact);
        ResourceKey exactKey = new ResourceKey(namespace, exact);
        ResourceKey otherObject = new ResourceKey(namespace, variant);
        ResourceKey otherCatalog =
                new ResourceKey(
                        new ResourceNamespace(
                                new ResourceId(1), DatabaseEngine.POSTGRESQL, variant, exact),
                        exact);
        ResourceKey otherSchema =
                new ResourceKey(
                        new ResourceNamespace(
                                new ResourceId(1), DatabaseEngine.POSTGRESQL, exact, variant),
                        exact);

        assertEquals(4, Set.of(exactKey, otherObject, otherCatalog, otherSchema).size());
    }

    @Test
    void canonicallyEquivalentUnicodeNamesStillHaveDifferentKeys() {
        ResourceKey composed = new ResourceKey(NAMESPACE, "é");
        ResourceKey decomposed = new ResourceKey(NAMESPACE, "e\u0301");

        assertNotEquals(composed, decomposed);
    }

    @Test
    void diagnosticsOmitObjectAndNamespaceNamesWithoutChangingAccessors() {
        ResourceNamespace namespace =
                new ResourceNamespace(
                        new ResourceId(1),
                        DatabaseEngine.POSTGRESQL,
                        "SYNTHETIC_PRIVATE_CATALOG_62fa",
                        "SYNTHETIC_PRIVATE_SCHEMA_62fa");
        String objectName = "SYNTHETIC_PRIVATE_OBJECT_62fa";
        ResourceKey key = new ResourceKey(namespace, objectName);

        assertAll(
                () -> assertEquals(namespace, key.namespace()),
                () -> assertEquals(objectName, key.objectName()),
                () ->
                        assertEquals(
                                "ResourceKey[namespace=ResourceNamespace[datasourceId=1, engine=POSTGRESQL]]",
                                key.toString()));
    }

    @Test
    void defaultResourceRefDiagnosticsOnlyRecurseIntoSafeKeyAndNumericVersions() {
        ResourceNamespace namespace =
                new ResourceNamespace(
                        new ResourceId(1),
                        DatabaseEngine.POSTGRESQL,
                        "SYNTHETIC_PRIVATE_CATALOG_62fa",
                        "SYNTHETIC_PRIVATE_SCHEMA_62fa");
        ResourceKey key = new ResourceKey(namespace, "SYNTHETIC_PRIVATE_OBJECT_62fa");
        ResourceRef reference = new ResourceRef(key, new ResourceId(2), 3, 4, 5);

        assertEquals(
                "ResourceRef[key=ResourceKey[namespace=ResourceNamespace[datasourceId=1, engine=POSTGRESQL]], "
                        + "objectId=2, generation=3, objectVersion=4, snapshotVersion=5]",
                reference.toString());
    }

    @Test
    void invalidObjectNameErrorsDoNotEchoSnapshotData() {
        IllegalArgumentException error =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> new ResourceKey(NAMESPACE, "SYNTHETIC_PRIVATE_OBJECT_62fa\0"));

        assertEquals(
                "objectName must be 1 to 128 Unicode codepoints without NUL or malformed UTF-16",
                error.getMessage());
    }
}
