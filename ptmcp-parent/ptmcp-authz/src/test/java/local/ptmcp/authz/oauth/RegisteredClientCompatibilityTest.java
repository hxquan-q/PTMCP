package local.ptmcp.authz.oauth;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;

import java.lang.reflect.Method;
import java.sql.ResultSet;
import java.util.Set;

class RegisteredClientCompatibilityTest {
    @Test
    void adminSpaceSeparatedScopesMustRoundTrip() throws Exception {
        ResultSet row = row("PUBLIC", "none", null);
        when(row.getString("scopes")).thenReturn("mcp:catalog mcp:schema mcp:query");
        RegisteredClient client = assertDoesNotThrow(() -> map(row));
        assertEquals(Set.of("mcp:catalog", "mcp:schema", "mcp:query"), client.getScopes());
    }

    @Test
    void adminNewlineSeparatedRedirectUrisMustRoundTrip() throws Exception {
        ResultSet row = row("PUBLIC", "none", null);
        when(row.getString("redirect_uris"))
                .thenReturn("https://review.example/one\nhttps://review.example/two");
        RegisteredClient client = assertDoesNotThrow(() -> map(row));
        assertEquals(
                Set.of("https://review.example/one", "https://review.example/two"),
                client.getRedirectUris());
    }

    @Test
    void legacyUnsupportedPrivateKeyJwtClientRemainsRejected() throws Exception {
        ResultSet row = row("CONFIDENTIAL", "private_key_jwt", null);
        assertNull(map(row), "unsupported legacy clients must not acquire a secret-auth fallback");
    }

    @Test
    void canonicalArrayFieldsPreserveCommaInRedirectUri() throws Exception {
        ResultSet row = row("PUBLIC", "none", null);
        when(row.getString("redirect_uris"))
                .thenReturn(
                        "[\"https://review.example/one?allow=a,b\",\"https://review.example/two\"]");
        when(row.getString("scopes")).thenReturn("[\"mcp:catalog\",\"mcp:schema\",\"mcp:query\"]");
        RegisteredClient client = assertDoesNotThrow(() -> map(row));
        assertEquals(
                Set.of("https://review.example/one?allow=a,b", "https://review.example/two"),
                client.getRedirectUris());
        assertEquals(Set.of("mcp:catalog", "mcp:schema", "mcp:query"), client.getScopes());
    }

    @Test
    void legacyCsvFieldsRemainReadable() throws Exception {
        ResultSet row = row("PUBLIC", "none", null);
        when(row.getString("redirect_uris"))
                .thenReturn("https://review.example/one,https://review.example/two");
        when(row.getString("scopes")).thenReturn("mcp:catalog,mcp:schema,mcp:query");
        RegisteredClient client = assertDoesNotThrow(() -> map(row));
        assertEquals(
                Set.of("https://review.example/one", "https://review.example/two"),
                client.getRedirectUris());
        assertEquals(Set.of("mcp:catalog", "mcp:schema", "mcp:query"), client.getScopes());
    }

    private static ResultSet row(String type, String method, String secret) throws Exception {
        ResultSet row = mock(ResultSet.class);
        when(row.getObject("platform_id", Long.class)).thenReturn(1L);
        when(row.getString("platform_status")).thenReturn("ACTIVE");
        when(row.getString("client_type")).thenReturn(type);
        when(row.getString("id")).thenReturn("review-row");
        when(row.getString("client_id")).thenReturn("review-client");
        when(row.getString("client_name")).thenReturn("Review client");
        when(row.getString("client_authentication_methods")).thenReturn(method);
        when(row.getString("authorization_grant_types"))
                .thenReturn("authorization_code,refresh_token");
        when(row.getString("redirect_uris")).thenReturn("https://review.example/callback");
        when(row.getString("scopes")).thenReturn("mcp:catalog");
        when(row.getString("client_secret")).thenReturn(secret);
        when(row.getString("client_settings")).thenReturn("{}");
        when(row.getString("jwks_uri")).thenReturn("https://review.example/jwks.json");
        return row;
    }

    private static RegisteredClient map(ResultSet row) throws Exception {
        OAuthProperties properties =
                new OAuthProperties(false, null, null, null, null, null, null, null);
        PtmcpRegisteredClientRepository repository =
                new PtmcpRegisteredClientRepository(mock(JdbcTemplate.class), properties);
        Method map =
                PtmcpRegisteredClientRepository.class.getDeclaredMethod("map", ResultSet.class);
        map.setAccessible(true);
        return (RegisteredClient) map.invoke(repository, row);
    }
}
