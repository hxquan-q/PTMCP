package local.ptmcp.gateway.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import local.ptmcp.datasource.ApprovedQueryAdapter;
import local.ptmcp.datasource.DataSourcePoolRegistry;
import local.ptmcp.datasource.connection.CredentialCipher;
import local.ptmcp.datasource.postgres.PgApprovedQueryAdapter;
import local.ptmcp.guard.budget.BoundedParser;
import local.ptmcp.persistence.audit.AuditRepository;
import local.ptmcp.persistence.audit.wal.TerminalWal;
import local.ptmcp.persistence.org.AuthorizationReader;
import local.ptmcp.persistence.schema.PublishedSnapshotReader;
import local.ptmcp.persistence.security.QuotaLimiter;
import local.ptmcp.persistence.security.SecurityStateReader;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

class McpToolMetadataTest {
    @Test
    void fourToolsExposeHintsAndMatchContractWithoutBusinessConnections() throws Exception {
        DataSourcePoolRegistry pools = mock(DataSourcePoolRegistry.class);
        McpToolService service =
                new McpToolService(
                        mock(SecurityStateReader.class),
                        mock(AuditRepository.class),
                        mock(TerminalWal.class),
                        mock(PublishedSnapshotReader.class),
                        mock(AuthorizationReader.class),
                        mock(CredentialCipher.class),
                        pools,
                        mock(ApprovedQueryAdapter.class),
                        mock(PgApprovedQueryAdapter.class),
                        mock(QuotaLimiter.class),
                        mock(BoundedParser.class),
                        mock(McpAuthentication.class),
                        mock(AuditSealer.class));
        McpToolService.Outcome outcome =
                service.handle(
                        null,
                        "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}",
                        UUID.randomUUID().toString());
        assertEquals(200, outcome.status());
        JsonMapper json = JsonMapper.builder().build();
        JsonNode tools = json.readTree(outcome.body()).path("result").path("tools");
        assertEquals(4, tools.size());
        for (JsonNode tool : tools) {
            assertFalse(tool.path("title").asText().isBlank(), tool.path("name").asText());
            assertTrue(tool.path("annotations").path("readOnlyHint").asBoolean(false));
            assertFalse(tool.path("annotations").path("destructiveHint").asBoolean(true));
            assertFalse(tool.path("annotations").path("openWorldHint").asBoolean(true));
            assertFalse(tool.path("description").asText().isBlank());
            assertFalse(tool.path("inputSchema").path("additionalProperties").asBoolean(true));
        }
        assertEquals(
                json.readTree(
                                Files.readString(
                                        Path.of("..", "..", "docs", "backend", "mcp-tools.json")))
                        .path("tools"),
                tools);
        verifyNoInteractions(pools);
    }
}
