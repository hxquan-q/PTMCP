package local.ptmcp.authz.platform;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import local.ptmcp.common.error.DomainException;
import local.ptmcp.common.error.ErrorCode;

import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;

/** CIMD/SSRF wall: public https/443 only, every resolved address checked, no private exceptions. */
class CimdPolicyTest {

    @Test
    void onlyHttpsOnDefaultPortIsAccepted() {
        assertDoesNotThrow(
                () -> CimdPolicy.validateUrl("https://client.example/.well-known/oauth-client"));
        assertDoesNotThrow(
                () ->
                        CimdPolicy.validateUrl(
                                "https://client.example:443/.well-known/oauth-client"));
        for (String bad :
                List.of(
                        "http://client.example/.well-known/oauth-client",
                        "https://client.example:8443/.well-known/oauth-client",
                        "https://user:pw@app.example/.well-known/oauth-client",
                        "https://client.example/.well-known/oauth-client#frag",
                        "ftp://client.example/x",
                        "https:///no-host",
                        "not a uri")) {
            DomainException error =
                    assertThrows(DomainException.class, () -> CimdPolicy.validateUrl(bad), bad);
            assertEquals(ErrorCode.INVALID_ARGUMENT, error.errorCode(), bad);
        }
    }

    @Test
    void loopbackAndMetadataAddressesAreForbidden() {
        for (String bad :
                List.of(
                        "127.0.0.1",
                        "127.8.8.8",
                        "::1",
                        "0.0.0.0",
                        "10.0.0.5",
                        "172.16.0.9",
                        "192.168.1.1",
                        "169.254.169.254",
                        "100.64.0.1",
                        "fe80::1",
                        "fc00::1",
                        "fd12:3456::1",
                        "::ffff:10.0.0.5",
                        "::ffff:169.254.169.254",
                        "224.0.0.1")) {
            DomainException error =
                    assertThrows(
                            DomainException.class,
                            () -> CimdPolicy.validateAddresses(new InetAddress[] {address(bad)}),
                            bad);
            assertEquals(ErrorCode.INVALID_ARGUMENT, error.errorCode(), bad);
        }
    }

    @Test
    void publicAddressesAreAllowed() {
        assertDoesNotThrow(
                () -> CimdPolicy.validateAddresses(new InetAddress[] {address("93.184.216.34")}));
        assertDoesNotThrow(
                () ->
                        CimdPolicy.validateAddresses(
                                new InetAddress[] {address("2606:2800:220:1:248:1893:25c8:1946")}));
    }

    @Test
    void emptyResolutionIsRejected() {
        DomainException error =
                assertThrows(
                        DomainException.class,
                        () -> CimdPolicy.validateAddresses(new InetAddress[] {}));
        assertEquals(ErrorCode.INVALID_ARGUMENT, error.errorCode());
    }

    private static InetAddress address(String value) {
        try {
            return InetAddress.getByName(value);
        } catch (UnknownHostException failure) {
            throw new IllegalStateException(failure);
        }
    }
}
