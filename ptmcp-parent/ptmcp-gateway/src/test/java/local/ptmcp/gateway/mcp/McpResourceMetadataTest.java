package local.ptmcp.gateway.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class McpResourceMetadataTest {
    private static final String RESOURCE = "https://gateway.example/mcp";
    private static final String ISSUER = "https://auth.example";
    private static final String METADATA =
            "https://gateway.example/.well-known/oauth-protected-resource";

    @Test
    void absentDiscoveryPreservesPatOnlyStartup() {
        var metadata = new McpResourceMetadata("", "", "", "");
        assertTrue(metadata.document().isEmpty());
        assertEquals("Bearer realm=\"PTMCP\"", metadata.challenge());
    }

    @Test
    void acceptsExplicitDefaultPortAndLoopbackDevelopment() {
        assertFalse(
                new McpResourceMetadata(
                                "", RESOURCE, ISSUER, METADATA.replace("example/", "example:443/"))
                        .document()
                        .isEmpty());
        for (String host : new String[] {"127.0.0.1", "[::1]", "localhost"}) {
            String origin = "http://" + host + ":8082";
            assertFalse(
                    new McpResourceMetadata(
                                    "",
                                    origin + "/mcp",
                                    "http://127.0.0.1:8083",
                                    origin + McpResourceMetadata.ROOT_PATH)
                            .document()
                            .isEmpty());
        }
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "",
                "https://user:secret@auth.example",
                "http://auth.example",
                "//auth.example",
                "https://auth.example?secret=value",
                "https://auth.example/#fragment",
                "https://auth.example\r\nInjected: value",
                "https://auth.example:0",
                "https://auth.example:65536",
                "https://auth.example\\evil",
                " https://auth.example"
            })
    void rejectsUnsafeIssuerWithoutLeakingValue(String issuer) {
        var failure =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> new McpResourceMetadata("", RESOURCE, issuer, METADATA));
        assertEquals("Invalid gateway OAuth discovery configuration", failure.getMessage());
        assertEquals(null, failure.getCause());
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "https://attacker.invalid/.well-known/oauth-protected-resource",
                "https://gateway.example:444/.well-known/oauth-protected-resource",
                "https://gateway.example/wrong",
                "https://gateway.example/.well-known/oauth-protected-resource?x=1",
                "https://gateway.example/.well-known/oauth-protected-resource#fragment",
                " "
            })
    void rejectsMismatchedMetadata(String uri) {
        assertThrows(
                IllegalArgumentException.class,
                () -> new McpResourceMetadata("", RESOURCE, ISSUER, uri));
    }

    @Test
    void invalidConfigurationPreventsContextStartup() {
        new ApplicationContextRunner()
                .withUserConfiguration(McpResourceMetadata.class)
                .withPropertyValues(
                        "ptmcp.gateway.resource=" + RESOURCE,
                        "ptmcp.gateway.issuer=" + ISSUER,
                        "ptmcp.gateway.resource-metadata-uri=https://attacker.invalid/metadata")
                .run(context -> assertTrue(context.getStartupFailure() != null));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new McpResourceMetadata(
                                "https://gateway.example/other", "", ISSUER, METADATA));
    }

    @Test
    void resourceUriIsPublishedWhileAudienceStaysOpaque() {
        var metadata = new McpResourceMetadata(RESOURCE, "ptmcp-mcp", ISSUER, METADATA);
        assertEquals(RESOURCE, metadata.document().get("resource"));
    }
}
