package local.ptmcp.common.resource;

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
    void engineIsPartOfThePhysicalNamespace() {
        ResourceKey mysql =
                new ResourceKey(
                        new ResourceNamespace(new ResourceId(1), DatabaseEngine.MYSQL, "erp", ""),
                        "orders");

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
    void invalidObjectNameErrorsDoNotEchoSnapshotData() {
        IllegalArgumentException error =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> new ResourceKey(NAMESPACE, "private\0"));

        assertEquals(
                "objectName must be 1 to 128 Unicode codepoints without NUL or malformed UTF-16",
                error.getMessage());
    }
}
