package local.ptmcp.common.resource;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Set;

class ResourceRefTest {
    private static final ResourceKey KEY =
            new ResourceKey(
                    new ResourceNamespace(
                            new ResourceId(1), DatabaseEngine.POSTGRESQL, "erp", "sales"),
                    "orders");
    private static final ResourceId OBJECT_ID = new ResourceId(2);

    @ParameterizedTest
    @ValueSource(longs = {1, Long.MAX_VALUE})
    void preservesTheFullPositiveLongRangeForGenerationsAndVersions(long version) {
        ResourceId objectId = new ResourceId(Long.MAX_VALUE);

        ResourceRef reference = new ResourceRef(KEY, objectId, version, version, version);

        assertEquals(KEY, reference.key());
        assertEquals(objectId, reference.objectId());
        assertEquals(version, reference.generation());
        assertEquals(version, reference.objectVersion());
        assertEquals(version, reference.snapshotVersion());
    }

    @Test
    void preservesIndependentGenerationAndVersionValuesWithoutInferringTheirOrdering() {
        ResourceRef reference = new ResourceRef(KEY, OBJECT_ID, Long.MAX_VALUE, 1, 2);

        assertEquals(Long.MAX_VALUE, reference.generation());
        assertEquals(1, reference.objectVersion());
        assertEquals(2, reference.snapshotVersion());
    }

    @Test
    void rejectsMissingKeyAndObjectIdentity() {
        assertAll(
                () ->
                        assertThrows(
                                NullPointerException.class,
                                () -> new ResourceRef(null, OBJECT_ID, 1, 1, 1)),
                () ->
                        assertThrows(
                                NullPointerException.class,
                                () -> new ResourceRef(KEY, null, 1, 1, 1)));
    }

    @ParameterizedTest
    @ValueSource(longs = {0, -1, Long.MIN_VALUE})
    void rejectsEveryNonpositiveGenerationOrVersionWithoutEchoingTheValue(long version) {
        IllegalArgumentException generationError =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> new ResourceRef(KEY, OBJECT_ID, version, 1, 1));
        IllegalArgumentException objectVersionError =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> new ResourceRef(KEY, OBJECT_ID, 1, version, 1));
        IllegalArgumentException snapshotVersionError =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> new ResourceRef(KEY, OBJECT_ID, 1, 1, version));

        assertEquals(
                "Resource generation and versions must be positive", generationError.getMessage());
        assertEquals(
                "Resource generation and versions must be positive",
                objectVersionError.getMessage());
        assertEquals(
                "Resource generation and versions must be positive",
                snapshotVersionError.getMessage());
    }

    @Test
    void exactIdentityAndVersionsProduceTheSameReference() {
        ResourceRef reference = new ResourceRef(KEY, OBJECT_ID, 1, 2, 3);
        ResourceRef same =
                new ResourceRef(
                        new ResourceKey(
                                new ResourceNamespace(
                                        new ResourceId(1),
                                        DatabaseEngine.POSTGRESQL,
                                        "erp",
                                        "sales"),
                                "orders"),
                        new ResourceId(2),
                        1,
                        2,
                        3);

        assertEquals(reference, same);
        assertEquals(reference.hashCode(), same.hashCode());
        assertEquals(1, Set.copyOf(List.of(reference, same)).size());
    }

    @Test
    void rebuildingTheSameNamedObjectDoesNotProduceTheSameReference() {
        ResourceRef original = new ResourceRef(KEY, OBJECT_ID, 1, 1, 1);
        ResourceRef rebuilt = new ResourceRef(KEY, new ResourceId(3), 2, 1, 2);

        assertEquals(original.key(), rebuilt.key());
        assertNotEquals(original, rebuilt);
    }

    @Test
    void eachPhysicalIdentityGenerationAndVersionFieldSeparatesReferences() {
        ResourceRef reference = new ResourceRef(KEY, OBJECT_ID, 1, 1, 1);
        List<ResourceRef> distinct =
                List.of(
                        reference,
                        new ResourceRef(
                                new ResourceKey(KEY.namespace(), "users"), OBJECT_ID, 1, 1, 1),
                        new ResourceRef(KEY, new ResourceId(3), 1, 1, 1),
                        new ResourceRef(KEY, OBJECT_ID, 2, 1, 1),
                        new ResourceRef(KEY, OBJECT_ID, 1, 2, 1),
                        new ResourceRef(KEY, OBJECT_ID, 1, 1, 2));

        assertEquals(6, Set.copyOf(distinct).size());
    }
}
