package local.ptmcp.common.platform;

import local.ptmcp.common.error.DomainException;
import local.ptmcp.common.error.ErrorCode;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;

/**
 * A closed IPv4/IPv6 network range used for proxy-secret whitelisting. Parsing accepts only textual
 * IP literals (never hostnames, so no resolution can happen) and rejects out-of-range prefix
 * lengths. {@code 0.0.0.0/0} is allowed only as an explicit full-range declaration.
 */
public final class CidrRange {
    private final byte[] network;
    private final int prefixBits;

    private CidrRange(byte[] network, int prefixBits) {
        this.network = network;
        this.prefixBits = prefixBits;
    }

    public static CidrRange parse(String text) {
        int slash = text == null ? -1 : text.lastIndexOf('/');
        if (slash <= 0 || slash == text.length() - 1) {
            throw new DomainException(ErrorCode.INVALID_ARGUMENT);
        }
        String addressText = text.substring(0, slash);
        InetAddress address = parseLiteral(addressText);
        int bits;
        try {
            bits = Integer.parseInt(text.substring(slash + 1));
        } catch (NumberFormatException failure) {
            throw new DomainException(ErrorCode.INVALID_ARGUMENT);
        }
        int maximum = address.getAddress().length * 8;
        if (bits < 0 || bits > maximum) {
            throw new DomainException(ErrorCode.INVALID_ARGUMENT);
        }
        return new CidrRange(unwrap(address).getAddress(), bits);
    }

    public boolean contains(InetAddress candidate) {
        byte[] candidateBytes = unwrap(candidate).getAddress();
        if (candidateBytes.length != network.length) {
            return false;
        }
        byte[] masked = new byte[candidateBytes.length];
        int fullBytes = prefixBits / 8;
        int remainderBits = prefixBits % 8;
        for (int index = 0; index < fullBytes; index++) {
            masked[index] = (byte) 0xff;
        }
        if (remainderBits > 0 && fullBytes < masked.length) {
            masked[fullBytes] = (byte) (0xff << (8 - remainderBits));
        }
        for (int index = 0; index < candidateBytes.length; index++) {
            if ((candidateBytes[index] & masked[index]) != (network[index] & masked[index])) {
                return false;
            }
        }
        return true;
    }

    private static InetAddress parseLiteral(String text) {
        if (!isTextualIpLiteral(text)) {
            throw new DomainException(ErrorCode.INVALID_ARGUMENT);
        }
        try {
            return InetAddress.getByName(text);
        } catch (UnknownHostException failure) {
            throw new DomainException(ErrorCode.INVALID_ARGUMENT);
        }
    }

    private static boolean isTextualIpLiteral(String text) {
        boolean mayBeIpv6 = text.indexOf(':') >= 0;
        for (int index = 0; index < text.length(); index++) {
            char current = text.charAt(index);
            boolean hex =
                    (current >= '0' && current <= '9')
                            || (current >= 'a' && current <= 'f')
                            || (current >= 'A' && current <= 'F');
            if (!hex && current != '.' && !(mayBeIpv6 && current == ':')) {
                return false;
            }
        }
        return true;
    }

    public static InetAddress unwrap(InetAddress address) {
        byte[] bytes = address.getAddress();
        if (bytes.length == 16
                && bytes[10] == 0
                && bytes[11] == 0
                && bytes[12] == (byte) 0xff
                && bytes[13] == (byte) 0xff) {
            try {
                return InetAddress.getByAddress(Arrays.copyOfRange(bytes, 12, 16));
            } catch (UnknownHostException failure) {
                throw new IllegalStateException(failure);
            }
        }
        return address;
    }
}
