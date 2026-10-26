package local.ptmcp.common.platform;

import local.ptmcp.common.error.DomainException;
import local.ptmcp.common.error.ErrorCode;

import java.net.InetAddress;
import java.util.Arrays;

/**
 * The per-request Tier1 proof material as observed by the connector layer: the bound platform, the
 * proof kind, and the raw evidence (mTLS certificate fingerprint or shared proxy secret) plus the
 * network source address. Construction validates only shape; whether the evidence matches the
 * platform's registered pin is decided by the verifier, never by the caller.
 */
public record PlatformProof(
        long platformId,
        Kind kind,
        byte[] clientCertificateFingerprint,
        byte[] proxySecret,
        InetAddress sourceAddress) {

    public enum Kind {
        MTLS,
        PROXY_SECRET
    }

    public PlatformProof {
        if (platformId <= 0 || kind == null || sourceAddress == null) {
            throw new DomainException(ErrorCode.INVALID_ARGUMENT);
        }
        if (clientCertificateFingerprint != null && clientCertificateFingerprint.length != 32) {
            throw new DomainException(ErrorCode.INVALID_ARGUMENT);
        }
        clientCertificateFingerprint =
                clientCertificateFingerprint == null
                        ? null
                        : Arrays.copyOf(clientCertificateFingerprint, 32);
        proxySecret = proxySecret == null ? null : Arrays.copyOf(proxySecret, proxySecret.length);
    }
}
