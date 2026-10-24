package local.ptmcp.authz.platform;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import local.ptmcp.common.error.DomainException;

import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.UnknownHostException;

/** CIDR whitelist used by the proxy-secret verifier. */
class CidrRangeTest {

    @Test
    void ipv4PrefixMatchingIsExactOnMaskBits() {
        CidrRange range = CidrRange.parse("10.20.0.0/16");
        assertTrue(range.contains(address("10.20.3.4")));
        assertTrue(range.contains(address("10.20.255.255")));
        assertFalse(range.contains(address("10.21.0.1")));
        assertFalse(range.contains(address("10.2.0.1")));
    }

    @Test
    void hostPrefixAndIpv6PrefixesMatch() {
        assertTrue(CidrRange.parse("10.20.3.4/32").contains(address("10.20.3.4")));
        assertFalse(CidrRange.parse("10.20.3.4/32").contains(address("10.20.3.5")));
        CidrRange ula = CidrRange.parse("fd00:1234::/32");
        assertTrue(ula.contains(address("fd00:1234:abcd::1")));
        assertFalse(ula.contains(address("fd00:4321::1")));
    }

    @Test
    void ipv4MappedIpv6IsUnwrappedBeforeMatching() {
        CidrRange range = CidrRange.parse("10.20.0.0/16");
        assertTrue(range.contains(address("::ffff:10.20.3.4")));
        assertFalse(range.contains(address("::ffff:10.21.0.1")));
    }

    @Test
    void invalidCidrTextIsRejected() {
        assertThrows(DomainException.class, () -> CidrRange.parse("10.0.0.0"));
        assertThrows(DomainException.class, () -> CidrRange.parse("10.0.0.0/33"));
        assertThrows(DomainException.class, () -> CidrRange.parse("10.0.0.0/-1"));
        assertThrows(DomainException.class, () -> CidrRange.parse("not-an-ip/8"));
        assertThrows(DomainException.class, () -> CidrRange.parse("2001:db8::/129"));
    }

    @Test
    void zeroPrefixIsAllowedOnlyAsExplicitFullRange() {
        assertDoesNotThrow(() -> CidrRange.parse("0.0.0.0/0"));
    }

    private static InetAddress address(String value) {
        try {
            return InetAddress.getByName(value);
        } catch (UnknownHostException failure) {
            throw new IllegalStateException(failure);
        }
    }
}
