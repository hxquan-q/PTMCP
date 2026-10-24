package local.ptmcp.authz.platform;

import local.ptmcp.common.error.DomainException;
import local.ptmcp.common.error.ErrorCode;

import java.net.InetAddress;
import java.security.MessageDigest;
import java.util.List;
import java.util.Map;

/**
 * Verifies the proxy-secret variant of the Tier1 per-request proof. The network source address must
 * fall inside one of the configured proxy CIDR ranges and the presented secret must match the
 * platform's registered secret in constant time; either failure is {@code PLATFORM_MISMATCH}, a
 * missing secret is {@code PLATFORM_PROOF_REQUIRED}.
 */
public final class ProxySecretProofVerifier {
    private final Map<Long, byte[]> platformSecrets;
    private final List<CidrRange> proxyNetworks;

    public ProxySecretProofVerifier(
            Map<Long, byte[]> platformSecrets, List<CidrRange> proxyNetworks) {
        this.platformSecrets =
                platformSecrets.entrySet().stream()
                        .collect(
                                java.util.stream.Collectors.toUnmodifiableMap(
                                        Map.Entry::getKey, entry -> entry.getValue().clone()));
        this.proxyNetworks = List.copyOf(proxyNetworks);
    }

    public void verify(PlatformProof proof) {
        if (proof.kind() != PlatformProof.Kind.PROXY_SECRET) {
            throw new DomainException(ErrorCode.PLATFORM_MISMATCH);
        }
        byte[] secret = proof.proxySecret();
        if (secret == null) {
            throw new DomainException(ErrorCode.PLATFORM_PROOF_REQUIRED);
        }
        InetAddress source = proof.sourceAddress();
        boolean insideProxyNetwork = false;
        for (CidrRange range : proxyNetworks) {
            if (range.contains(source)) {
                insideProxyNetwork = true;
                break;
            }
        }
        if (!insideProxyNetwork) {
            throw new DomainException(ErrorCode.PLATFORM_MISMATCH);
        }
        byte[] expected = platformSecrets.get(proof.platformId());
        if (expected == null || !MessageDigest.isEqual(expected, secret)) {
            throw new DomainException(ErrorCode.PLATFORM_MISMATCH);
        }
    }
}
