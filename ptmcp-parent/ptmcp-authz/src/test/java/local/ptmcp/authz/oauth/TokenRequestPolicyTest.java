package local.ptmcp.authz.oauth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;

class TokenRequestPolicyTest {
    private static final String RESOURCE = "https://gateway.example/mcp";
    private final TokenRequestPolicy policy = new TokenRequestPolicy(RESOURCE);

    @Test
    void tokenParametersMustComeFromOneFormWithoutDuplicates() {
        MockHttpServletRequest query = request();
        query.setQueryString("resource=" + RESOURCE);
        assertInvalidRequest(query);

        MockHttpServletRequest json = request();
        json.setContentType("application/json");
        assertInvalidRequest(json);

        MockHttpServletRequest repeated = request();
        repeated.addParameter("refresh_token", "one", "two");
        assertInvalidRequest(repeated);
    }

    private void assertInvalidRequest(MockHttpServletRequest request) {
        var error =
                assertThrows(OAuth2AuthenticationException.class, () -> policy.convert(request));
        assertEquals("invalid_request", error.getError().getErrorCode());
    }

    private static MockHttpServletRequest request() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/oauth2/token");
        request.setContentType("application/x-www-form-urlencoded");
        request.addParameter("grant_type", "refresh_token");
        request.addParameter("resource", RESOURCE);
        return request;
    }
}
