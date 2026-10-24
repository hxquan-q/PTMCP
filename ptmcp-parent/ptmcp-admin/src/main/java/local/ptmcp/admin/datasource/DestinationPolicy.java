package local.ptmcp.admin.datasource;

import local.ptmcp.common.error.DomainException;
import local.ptmcp.common.error.ErrorCode;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Destination policy for datasource endpoints: the host must resolve to addresses inside the
 * operator CIDR allowlist, and link-local/metadata/multicast targets stay blocked even when an
 * allowlist would cover them. An empty allowlist denies every destination.
 */
public final class DestinationPolicy {
    private static final byte[] METADATA_V4 = {(byte) 169, (byte) 254, (byte) 169, (byte) 254};
    private static final byte[] METADATA_V6 = parseLiteral("fd00:ec2::254");

    private final List<Cidr> allowed;

    public DestinationPolicy(String allowedCidrs) {
        List<Cidr> parsed = new ArrayList<>();
        if (allowedCidrs != null) {
            for (String entry : allowedCidrs.split(",")) {
                String value = entry.trim();
                if (!value.isEmpty()) {
                    parsed.add(Cidr.parse(value));
                }
            }
        }
        this.allowed = List.copyOf(parsed);
    }

    public void validate(String host, int port) {
        String normalized = normalizeHost(host);
        if (port < 1 || port > 65535) {
            throw new DomainException(ErrorCode.INVALID_ARGUMENT);
        }
        InetAddress[] resolved;
        try {
            resolved = InetAddress.getAllByName(normalized);
        } catch (UnknownHostException error) {
            throw new DomainException(ErrorCode.INVALID_ARGUMENT);
        }
        if (resolved.length == 0) {
            throw new DomainException(ErrorCode.INVALID_ARGUMENT);
        }
        for (InetAddress address : resolved) {
            if (forbidden(address) || !allowedByAny(address)) {
                throw new DomainException(ErrorCode.INVALID_ARGUMENT);
            }
        }
    }

    static String normalizeHost(String host) {
        if (host == null) {
            throw new DomainException(ErrorCode.INVALID_ARGUMENT);
        }
        String value = host.trim();
        if (value.isEmpty()
                || value.length() > 253
                || value.startsWith("[")
                || value.endsWith("]")
                || value.toLowerCase(Locale.ROOT).startsWith("jdbc:")
                || value.contains("/")
                || value.contains("\\")
                || value.contains("@")
                || value.contains("?")
                || value.contains("#")
                || value.contains("%")
                || !value.matches("[A-Za-z0-9._:-]+")) {
            throw new DomainException(ErrorCode.INVALID_ARGUMENT);
        }
        return value;
    }

    private boolean forbidden(InetAddress address) {
        byte[] raw = address.getAddress();
        if (address.isLinkLocalAddress()
                || address.isMulticastAddress()
                || address.isAnyLocalAddress()) {
            return true;
        }
        if (raw.length == 4 && Arrays.equals(raw, METADATA_V4)) {
            return true;
        }
        return raw.length == 16 && Arrays.equals(raw, METADATA_V6);
    }

    private boolean allowedByAny(InetAddress address) {
        for (Cidr cidr : allowed) {
            if (cidr.contains(address)) {
                return true;
            }
        }
        return false;
    }

    private record Cidr(byte[] network, int prefix) {
        static Cidr parse(String value) {
            int slash = value.indexOf('/');
            if (slash <= 0 || slash == value.length() - 1) {
                throw new IllegalArgumentException("CIDR must be address/prefix");
            }
            String addressPart = value.substring(0, slash);
            if (!addressPart.matches("[0-9A-Fa-f:.]+")) {
                throw new IllegalArgumentException("CIDR must use a literal address");
            }
            InetAddress address;
            try {
                address = InetAddress.getByName(addressPart);
            } catch (UnknownHostException error) {
                throw new IllegalArgumentException("invalid CIDR address", error);
            }
            int prefix;
            try {
                prefix = Integer.parseInt(value.substring(slash + 1));
            } catch (NumberFormatException error) {
                throw new IllegalArgumentException("invalid CIDR prefix", error);
            }
            byte[] raw = address.getAddress();
            if (prefix < 0 || prefix > raw.length * 8) {
                throw new IllegalArgumentException("CIDR prefix out of range");
            }
            byte[] network = new byte[raw.length];
            for (int index = 0; index < raw.length; index++) {
                int bits = Math.max(0, Math.min(8, prefix - index * 8));
                int mask = bits == 0 ? 0 : (0xFF << (8 - bits)) & 0xFF;
                network[index] = (byte) (raw[index] & mask);
            }
            return new Cidr(network, prefix);
        }

        boolean contains(InetAddress address) {
            byte[] raw = address.getAddress();
            if (raw.length != network.length) {
                return false;
            }
            for (int index = 0; index < raw.length; index++) {
                int bits = Math.max(0, Math.min(8, prefix - index * 8));
                int mask = bits == 0 ? 0 : (0xFF << (8 - bits)) & 0xFF;
                if ((raw[index] & mask) != (network[index] & mask)) {
                    return false;
                }
            }
            return true;
        }
    }

    private static byte[] parseLiteral(String literal) {
        try {
            return InetAddress.getByName(literal).getAddress();
        } catch (UnknownHostException error) {
            throw new IllegalStateException(error);
        }
    }
}
