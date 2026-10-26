package local.ptmcp.common.platform;

import local.ptmcp.common.error.DomainException;
import local.ptmcp.common.error.ErrorCode;

import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Verifies the mTLS variant of the Tier1 per-request proof against a platform-bound certificate
 * fingerprint whitelist. A missing fingerprint fails closed with {@code PLATFORM_PROOF_REQUIRED}; a
 * present fingerprint that is not pinned to the bound platform is {@code PLATFORM_MISMATCH}.
 * Client-supplied identifiers never constitute proof on their own.
 */
public final class MtlsProofVerifier {
    private final Map<Long, Set<String>> pinnedFingerprints;

    public MtlsProofVerifier(Map<Long, Set<String>> pinnedFingerprints) {
        this.pinnedFingerprints =
                pinnedFingerprints.entrySet().stream()
                        .collect(
                                Collectors.toUnmodifiableMap(
                                        Map.Entry::getKey,
                                        entry ->
                                                entry.getValue().stream()
                                                        .map(
                                                                value ->
                                                                        value.toLowerCase(
                                                                                Locale.ROOT))
                                                        .collect(Collectors.toUnmodifiableSet())));
    }

    public void verify(PlatformProof proof) {
        if (proof.kind() != PlatformProof.Kind.MTLS) {
            throw new DomainException(ErrorCode.PLATFORM_MISMATCH);
        }
        byte[] fingerprint = proof.clientCertificateFingerprint();
        if (fingerprint == null) {
            throw new DomainException(ErrorCode.PLATFORM_PROOF_REQUIRED);
        }
        Set<String> pinned = pinnedFingerprints.get(proof.platformId());
        if (pinned == null || pinned.isEmpty()) {
            throw new DomainException(ErrorCode.PLATFORM_MISMATCH);
        }
        String presented = HexFormat.of().formatHex(fingerprint);
        for (String expected : pinned) {
            if (expected.equals(presented)) {
                return;
            }
        }
        throw new DomainException(ErrorCode.PLATFORM_MISMATCH);
    }
}
