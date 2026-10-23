package local.ptmcp.common.resource;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Set;

class ResourceIdentifierTest {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void retainsExplicitRawAndNormalizedSpellingWithoutInferringRulesFromQuoted(boolean quoted) {
        String raw = " Orders.é ";
        String normalized = " Explicit.e\u0301 ";

        ResourceIdentifier identifier = new ResourceIdentifier(raw, normalized, quoted);

        assertEquals(raw, identifier.raw());
        assertEquals(normalized, identifier.normalized());
        assertEquals(quoted, identifier.quoted());
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                " ",
                "select",
                "a.b",
                "a\"b",
                "a`b",
                "\"Orders\"",
                "O'Reilly",
                "é",
                "e\u0301",
                "表\uD83D\uDE80",
                "table; DROP TABLE x --",
                "\t"
            })
    void preservesDecodedNameDataInsteadOfParsingOrRenderingSqlTokens(String name) {
        ResourceIdentifier unquoted = new ResourceIdentifier(name, name, false);
        ResourceIdentifier quoted = new ResourceIdentifier(name, name, true);

        assertEquals(name, unquoted.raw());
        assertEquals(name, unquoted.normalized());
        assertEquals(name, quoted.raw());
        assertEquals(name, quoted.normalized());
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(
            strings = {
                "\0",
                "a\0b",
                "\uD800",
                "a\uD800b",
                "\uDC00",
                "a\uDC00b",
                "\uDC00\uD800",
                "\uD800\uD800",
                "\uD83D\uDE80\uDC00"
            })
    void validatesBothNamesRegardlessOfQuotedFlag(String name) {
        assertAll(
                () ->
                        assertThrows(
                                IllegalArgumentException.class,
                                () -> new ResourceIdentifier(name, "orders", false)),
                () ->
                        assertThrows(
                                IllegalArgumentException.class,
                                () -> new ResourceIdentifier(name, "orders", true)),
                () ->
                        assertThrows(
                                IllegalArgumentException.class,
                                () -> new ResourceIdentifier("orders", name, false)),
                () ->
                        assertThrows(
                                IllegalArgumentException.class,
                                () -> new ResourceIdentifier("orders", name, true)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"a", "\uD83D\uDE80"})
    void accepts128CodepointsAndRejects129IndependentlyInBothNames(String character) {
        String maximum = character.repeat(128);
        String tooLong = character.repeat(129);

        ResourceIdentifier identifier = new ResourceIdentifier(maximum, maximum, true);

        assertEquals(maximum, identifier.raw());
        assertEquals(maximum, identifier.normalized());
        assertAll(
                () ->
                        assertThrows(
                                IllegalArgumentException.class,
                                () -> new ResourceIdentifier(tooLong, "orders", false)),
                () ->
                        assertThrows(
                                IllegalArgumentException.class,
                                () -> new ResourceIdentifier(tooLong, "orders", true)),
                () ->
                        assertThrows(
                                IllegalArgumentException.class,
                                () -> new ResourceIdentifier("orders", tooLong, false)),
                () ->
                        assertThrows(
                                IllegalArgumentException.class,
                                () -> new ResourceIdentifier("orders", tooLong, true)));
    }

    @Test
    void countsCombiningMarksAsCodepointsRatherThanGraphemes() {
        String maximum = "e\u0301".repeat(64);
        String tooLong = maximum + "e";

        ResourceIdentifier identifier = new ResourceIdentifier(maximum, maximum, false);

        assertEquals(maximum, identifier.raw());
        assertEquals(maximum, identifier.normalized());
        assertAll(
                () ->
                        assertThrows(
                                IllegalArgumentException.class,
                                () -> new ResourceIdentifier(tooLong, "orders", false)),
                () ->
                        assertThrows(
                                IllegalArgumentException.class,
                                () -> new ResourceIdentifier("orders", tooLong, false)));
    }

    @Test
    void equalityIncludesBothExactSpellingsAndTheQuotedFlag() {
        ResourceIdentifier identifier = new ResourceIdentifier("Orders", "orders", false);
        ResourceIdentifier same = new ResourceIdentifier("Orders", "orders", false);
        List<ResourceIdentifier> distinct =
                List.of(
                        identifier,
                        new ResourceIdentifier("orders", "orders", false),
                        new ResourceIdentifier("Orders", "Orders", false),
                        new ResourceIdentifier("Orders", "orders", true));

        assertEquals(identifier, same);
        assertEquals(identifier.hashCode(), same.hashCode());
        assertEquals(4, Set.copyOf(distinct).size());
    }

    @Test
    void canonicallyEquivalentSpellingsAreNotMerged() {
        ResourceIdentifier composed = new ResourceIdentifier("é", "é", true);
        ResourceIdentifier rawDecomposed = new ResourceIdentifier("e\u0301", "é", true);
        ResourceIdentifier normalizedDecomposed = new ResourceIdentifier("é", "e\u0301", true);

        assertNotEquals(composed, rawDecomposed);
        assertNotEquals(composed, normalizedDecomposed);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void diagnosticsOmitBothSpellingsWithoutChangingAccessors(boolean quoted) {
        String raw = "SYNTHETIC_PRIVATE_RAW_62fa";
        String normalized = "SYNTHETIC_PRIVATE_NORMALIZED_62fa";
        ResourceIdentifier identifier = new ResourceIdentifier(raw, normalized, quoted);

        assertAll(
                () -> assertEquals(raw, identifier.raw()),
                () -> assertEquals(normalized, identifier.normalized()),
                () -> assertEquals(quoted, identifier.quoted()),
                () ->
                        assertEquals(
                                "ResourceIdentifier[quoted=" + quoted + "]",
                                identifier.toString()));
    }

    @Test
    void invalidNameErrorsDoNotEchoRawOrNormalizedSnapshotData() {
        IllegalArgumentException rawError =
                assertThrows(
                        IllegalArgumentException.class,
                        () ->
                                new ResourceIdentifier(
                                        "SYNTHETIC_PRIVATE_RAW_62fa\0", "orders", true));
        IllegalArgumentException normalizedError =
                assertThrows(
                        IllegalArgumentException.class,
                        () ->
                                new ResourceIdentifier(
                                        "orders", "SYNTHETIC_PRIVATE_NORMALIZED_62fa\0", true));

        assertEquals(
                "raw must be 1 to 128 Unicode codepoints without NUL or malformed UTF-16",
                rawError.getMessage());
        assertEquals(
                "normalized must be 1 to 128 Unicode codepoints without NUL or malformed UTF-16",
                normalizedError.getMessage());
    }
}
