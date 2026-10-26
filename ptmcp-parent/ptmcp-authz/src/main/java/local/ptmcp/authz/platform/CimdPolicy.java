package local.ptmcp.authz.platform;

import local.ptmcp.common.error.DomainException;
import local.ptmcp.common.error.ErrorCode;
import local.ptmcp.common.platform.CidrRange;

import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;

/**
 * The CIMD/SSRF wall as pure policy: metadata URLs are public https on the default or explicit 443
 * port only (no userinfo, no fragment), and every resolved address — including every address of a
 * multi-answer resolution — must be publicly routable. There is no private-network exception:
 * loopback, RFC 1918, ULA, CGNAT, link-local, multicast and the cloud-metadata range are all
 * refused. Business-datasource CIDR rules are deliberately not reused here.
 */
public final class CimdPolicy {
    private CimdPolicy() {}

    public static void validateUrl(String value) {
        URI uri;
        try {
            uri = new URI(value);
        } catch (URISyntaxException failure) {
            throw new DomainException(ErrorCode.INVALID_ARGUMENT);
        }
        if (!"https".equalsIgnoreCase(uri.getScheme())
                || uri.getHost() == null
                || uri.getHost().isEmpty()
                || (uri.getPort() != -1 && uri.getPort() != 443)
                || uri.getUserInfo() != null
                || uri.getFragment() != null) {
            throw new DomainException(ErrorCode.INVALID_ARGUMENT);
        }
    }

    public static void validateAddresses(InetAddress[] addresses) {
        if (addresses == null || addresses.length == 0) {
            throw new DomainException(ErrorCode.INVALID_ARGUMENT);
        }
        for (InetAddress address : addresses) {
            if (!isPubliclyRoutable(address)) {
                throw new DomainException(ErrorCode.INVALID_ARGUMENT);
            }
        }
    }

    static boolean isPubliclyRoutable(InetAddress candidate) {
        InetAddress address = CidrRange.unwrap(candidate);
        if (address.isLoopbackAddress()
                || address.isAnyLocalAddress()
                || address.isLinkLocalAddress()
                || address.isMulticastAddress()
                || address.isSiteLocalAddress()) {
            return false;
        }
        byte[] bytes = address.getAddress();
        if (bytes.length == 4) {
            int firstOctet = bytes[0] & 0xff;
            int secondOctet = bytes[1] & 0xff;
            return !(firstOctet == 100 && secondOctet >= 64 && secondOctet <= 127);
        }
        return (bytes[0] & 0xfe) != 0xfc;
    }
}
