package local.ptmcp.authz.platform;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import local.ptmcp.common.error.DomainException;
import local.ptmcp.common.error.ErrorCode;

import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Tier1 per-request proof: missing proof is REQUIRED, wrong proof is MISMATCH. */
class PlatformProofVerifierTest {

    @Test
    void missingClientCertificateFailsClosedAsProofRequired() {
        MtlsProofVerifier verifier = new MtlsProofVerifier(Map.of(1L, Set.of("aa".repeat(32))));
        PlatformProof anonymous =
                new PlatformProof(1L, PlatformProof.Kind.MTLS, null, null, local("10.1.2.3"));
        DomainException error =
                assertThrows(DomainException.class, () -> verifier.verify(anonymous));
        assertEquals(ErrorCode.PLATFORM_PROOF_REQUIRED, error.errorCode());
    }

    @Test
    void unpinnedCertificateIsPlatformMismatchNotRequired() {
        MtlsProofVerifier verifier = new MtlsProofVerifier(Map.of(1L, Set.of("aa".repeat(32))));
        PlatformProof rogue =
                new PlatformProof(
                        1L,
                        PlatformProof.Kind.MTLS,
                        HexFormat.of().parseHex("bb".repeat(32)),
                        null,
                        local("10.1.2.3"));
        DomainException error = assertThrows(DomainException.class, () -> verifier.verify(rogue));
        assertEquals(ErrorCode.PLATFORM_MISMATCH, error.errorCode());
    }

    @Test
    void pinnedCertificateOfAnotherPlatformIsMismatch() {
        MtlsProofVerifier verifier = new MtlsProofVerifier(Map.of(1L, Set.of("aa".repeat(32))));
        PlatformProof crossPlatform =
                new PlatformProof(
                        2L,
                        PlatformProof.Kind.MTLS,
                        HexFormat.of().parseHex("aa".repeat(32)),
                        null,
                        local("10.1.2.3"));
        DomainException error =
                assertThrows(DomainException.class, () -> verifier.verify(crossPlatform));
        assertEquals(ErrorCode.PLATFORM_MISMATCH, error.errorCode());
    }

    @Test
    void pinnedCertificateOfTheBoundPlatformPasses() {
        MtlsProofVerifier verifier = new MtlsProofVerifier(Map.of(1L, Set.of("aa".repeat(32))));
        PlatformProof trusted =
                new PlatformProof(
                        1L,
                        PlatformProof.Kind.MTLS,
                        HexFormat.of().parseHex("aa".repeat(32)),
                        null,
                        local("10.1.2.3"));
        assertDoesNotThrow(() -> verifier.verify(trusted));
    }

    @Test
    void proxySecretOutsideTheProxyNetworkIsMismatch() {
        ProxySecretProofVerifier verifier =
                new ProxySecretProofVerifier(
                        Map.of(1L, "proxy-secret-1".getBytes()),
                        List.of(CidrRange.parse("10.20.0.0/16")));
        PlatformProof outside =
                new PlatformProof(
                        1L,
                        PlatformProof.Kind.PROXY_SECRET,
                        null,
                        "proxy-secret-1".getBytes(),
                        local("192.168.5.5"));
        DomainException error = assertThrows(DomainException.class, () -> verifier.verify(outside));
        assertEquals(ErrorCode.PLATFORM_MISMATCH, error.errorCode());
    }

    @Test
    void proxySecretInsideNetworkWithWrongSecretIsMismatch() {
        ProxySecretProofVerifier verifier =
                new ProxySecretProofVerifier(
                        Map.of(1L, "proxy-secret-1".getBytes()),
                        List.of(CidrRange.parse("10.20.0.0/16")));
        PlatformProof wrongSecret =
                new PlatformProof(
                        1L,
                        PlatformProof.Kind.PROXY_SECRET,
                        null,
                        "wrong-secret".getBytes(),
                        local("10.20.3.4"));
        DomainException error =
                assertThrows(DomainException.class, () -> verifier.verify(wrongSecret));
        assertEquals(ErrorCode.PLATFORM_MISMATCH, error.errorCode());
    }

    @Test
    void proxySecretMissingIsProofRequired() {
        ProxySecretProofVerifier verifier =
                new ProxySecretProofVerifier(
                        Map.of(1L, "proxy-secret-1".getBytes()),
                        List.of(CidrRange.parse("10.20.0.0/16")));
        PlatformProof anonymous =
                new PlatformProof(
                        1L, PlatformProof.Kind.PROXY_SECRET, null, null, local("10.20.3.4"));
        DomainException error =
                assertThrows(DomainException.class, () -> verifier.verify(anonymous));
        assertEquals(ErrorCode.PLATFORM_PROOF_REQUIRED, error.errorCode());
    }

    @Test
    void proxySecretMatchingWithinNetworkPasses() {
        ProxySecretProofVerifier verifier =
                new ProxySecretProofVerifier(
                        Map.of(1L, "proxy-secret-1".getBytes()),
                        List.of(CidrRange.parse("10.20.0.0/16")));
        PlatformProof trusted =
                new PlatformProof(
                        1L,
                        PlatformProof.Kind.PROXY_SECRET,
                        null,
                        "proxy-secret-1".getBytes(),
                        local("10.20.3.4"));
        assertDoesNotThrow(() -> verifier.verify(trusted));
    }

    @Test
    void proofWithoutPlatformOrKindIsInvalidArgument() {
        MtlsProofVerifier verifier = new MtlsProofVerifier(Map.of());
        assertThrows(
                DomainException.class,
                () ->
                        verifier.verify(
                                new PlatformProof(
                                        0L,
                                        PlatformProof.Kind.MTLS,
                                        new byte[32],
                                        null,
                                        local("10.1.2.3"))));
        assertThrows(
                DomainException.class,
                () ->
                        verifier.verify(
                                new PlatformProof(
                                        1L, null, new byte[32], null, local("10.1.2.3"))));
        assertThrows(
                DomainException.class,
                () ->
                        verifier.verify(
                                new PlatformProof(
                                        1L,
                                        PlatformProof.Kind.MTLS,
                                        new byte[16],
                                        null,
                                        local("10.1.2.3"))));
    }

    private static InetAddress local(String address) {
        try {
            return InetAddress.getByName(address);
        } catch (UnknownHostException failure) {
            throw new IllegalStateException(failure);
        }
    }
}
