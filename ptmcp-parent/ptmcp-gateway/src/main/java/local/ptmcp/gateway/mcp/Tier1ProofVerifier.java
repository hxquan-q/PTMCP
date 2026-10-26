package local.ptmcp.gateway.mcp;

import jakarta.servlet.http.HttpServletRequest;

import local.ptmcp.common.error.DomainException;
import local.ptmcp.common.error.ErrorCode;
import local.ptmcp.common.platform.CidrRange;
import local.ptmcp.common.platform.MtlsProofVerifier;
import local.ptmcp.common.platform.PlatformProof;
import local.ptmcp.common.platform.ProxySecretProofVerifier;
import local.ptmcp.datasource.connection.CredentialCipher;
import local.ptmcp.persistence.org.AuthorizationReader;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Enforces the Tier1 per-request proof: the platform's encrypted policy is loaded from the primary
 * metadata row, decrypted with the deployment cipher, and the request must present either a pinned
 * mTLS certificate fingerprint or the proxy secret from an allowed proxy network. A missing proof
 * is {@code PLATFORM_PROOF_REQUIRED}; a wrong/untrusted proof is {@code PLATFORM_MISMATCH}. No
 * request header can upgrade a platform by itself: proxy-forwarded material is only read from a
 * configured proxy CIDR, and the bound platform comes from the authenticated credential.
 */
final class Tier1ProofVerifier {
    static final String CLIENT_CERT_FINGERPRINT_HEADER = "X-PTMCP-Client-Cert-Fingerprint";
    static final String PROXY_SECRET_HEADER = "X-PTMCP-Proxy-Secret";
    private static final String X509_ATTRIBUTE = "jakarta.servlet.request.X509Certificate";
    private static final String LEGACY_X509_ATTRIBUTE = "javax.servlet.request.X509Certificate";
    private static final int MAX_FINGERPRINTS = 20;
    private static final int MAX_PROXY_CIDRS = 20;
    private static final int MAX_SECRET_BYTES = 256;
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final AuthorizationReader authorization;
    private final CredentialCipher cipher;

    Tier1ProofVerifier(AuthorizationReader authorization, CredentialCipher cipher) {
        this.authorization = authorization;
        this.cipher = cipher;
    }

    void verify(McpIdentity identity, HttpServletRequest request) {
        AuthorizationReader.PlatformInfo platform =
                authorization.loadPlatform(identity.platformId());
        if (!platform.active()) {
            throw new DomainException(ErrorCode.TOKEN_INVALID);
        }
        if (platform.tier() != 1 || "NONE".equals(platform.proofMode())) {
            return;
        }
        AuthorizationReader.PlatformProofConfig stored =
                authorization.loadPlatformProofConfig(identity.platformId());
        if (stored.configCipher() == null
                || stored.configKeyId() == null
                || stored.configKeyId().isBlank()) {
            throw new DomainException(ErrorCode.PLATFORM_PROOF_REQUIRED);
        }
        Policy policy;
        try {
            policy = parse(cipher.decrypt(stored.configCipher(), stored.configKeyId()));
        } catch (DomainException failure) {
            throw new DomainException(ErrorCode.SECURITY_STATE_UNAVAILABLE);
        }
        if (!policy.mode().equals(stored.proofMode())) {
            throw new DomainException(ErrorCode.SECURITY_STATE_UNAVAILABLE);
        }
        InetAddress source = sourceAddress(request);
        if ("MTLS".equals(policy.mode())) {
            byte[] fingerprint = clientCertificateFingerprint(request);
            if (fingerprint == null && sourceIn(policy.proxyCidrs(), source)) {
                fingerprint = headerFingerprint(request);
            }
            new MtlsProofVerifier(Map.of(identity.platformId(), policy.fingerprints()))
                    .verify(
                            new PlatformProof(
                                    identity.platformId(),
                                    PlatformProof.Kind.MTLS,
                                    fingerprint,
                                    null,
                                    source));
            return;
        }
        byte[] secret = headerSecret(request);
        new ProxySecretProofVerifier(
                        Map.of(identity.platformId(), policy.secret()), policy.proxyCidrs())
                .verify(
                        new PlatformProof(
                                identity.platformId(),
                                PlatformProof.Kind.PROXY_SECRET,
                                null,
                                secret,
                                source));
    }

    private static Policy parse(String json) {
        if (json == null || json.isBlank()) {
            throw new DomainException(ErrorCode.SECURITY_STATE_UNAVAILABLE);
        }
        JsonNode root;
        try {
            root = JSON.readTree(json);
        } catch (RuntimeException failure) {
            throw new DomainException(ErrorCode.SECURITY_STATE_UNAVAILABLE);
        }
        String mode = text(root, "mode");
        if (!"MTLS".equals(mode) && !"PROXY_SECRET".equals(mode)) {
            throw new DomainException(ErrorCode.SECURITY_STATE_UNAVAILABLE);
        }
        List<CidrRange> proxyCidrs = cidrs(root.path("proxyCidrs"));
        if ("MTLS".equals(mode)) {
            Set<String> fingerprints = fingerprints(root.path("fingerprints"));
            return new Policy(mode, fingerprints, null, proxyCidrs);
        }
        byte[] secret = secret(root.path("secret"));
        if (proxyCidrs.isEmpty() && secret == null) {
            throw new DomainException(ErrorCode.SECURITY_STATE_UNAVAILABLE);
        }
        return new Policy(mode, Set.of(), secret, proxyCidrs);
    }

    private static Set<String> fingerprints(JsonNode node) {
        if (!node.isArray() || node.isEmpty() || node.size() > MAX_FINGERPRINTS) {
            throw new DomainException(ErrorCode.SECURITY_STATE_UNAVAILABLE);
        }
        Set<String> values = new HashSet<>();
        for (JsonNode item : node) {
            String value = item.isTextual() ? item.asText().trim().toLowerCase(Locale.ROOT) : "";
            if (!value.matches("[0-9a-f]{64}")) {
                throw new DomainException(ErrorCode.SECURITY_STATE_UNAVAILABLE);
            }
            values.add(value);
        }
        return Set.copyOf(values);
    }

    private static byte[] secret(JsonNode node) {
        if (!node.isTextual() || node.asText().isBlank()) {
            throw new DomainException(ErrorCode.SECURITY_STATE_UNAVAILABLE);
        }
        try {
            byte[] decoded = Base64.getDecoder().decode(node.asText().trim());
            if (decoded.length == 0 || decoded.length > MAX_SECRET_BYTES) {
                throw new DomainException(ErrorCode.SECURITY_STATE_UNAVAILABLE);
            }
            return decoded;
        } catch (IllegalArgumentException failure) {
            throw new DomainException(ErrorCode.SECURITY_STATE_UNAVAILABLE);
        }
    }

    private static List<CidrRange> cidrs(JsonNode node) {
        if (node.isMissingNode() || node.isNull()) {
            return List.of();
        }
        if (!node.isArray() || node.size() > MAX_PROXY_CIDRS) {
            throw new DomainException(ErrorCode.SECURITY_STATE_UNAVAILABLE);
        }
        List<CidrRange> ranges = new ArrayList<>(node.size());
        for (JsonNode item : node) {
            if (!item.isTextual()) {
                throw new DomainException(ErrorCode.SECURITY_STATE_UNAVAILABLE);
            }
            try {
                ranges.add(CidrRange.parse(item.asText().trim()));
            } catch (DomainException failure) {
                throw new DomainException(ErrorCode.SECURITY_STATE_UNAVAILABLE);
            }
        }
        return List.copyOf(ranges);
    }

    private static String text(JsonNode root, String field) {
        JsonNode value = root.path(field);
        return value.isTextual() ? value.asText().trim() : "";
    }

    private static boolean sourceIn(List<CidrRange> ranges, InetAddress source) {
        for (CidrRange range : ranges) {
            if (range.contains(source)) {
                return true;
            }
        }
        return false;
    }

    private static InetAddress sourceAddress(HttpServletRequest request) {
        try {
            return InetAddress.getByName(request.getRemoteAddr());
        } catch (UnknownHostException failure) {
            throw new DomainException(ErrorCode.PLATFORM_MISMATCH);
        }
    }

    private static byte[] clientCertificateFingerprint(HttpServletRequest request) {
        X509Certificate[] certificates = (X509Certificate[]) request.getAttribute(X509_ATTRIBUTE);
        if (certificates == null || certificates.length == 0) {
            certificates = (X509Certificate[]) request.getAttribute(LEGACY_X509_ATTRIBUTE);
        }
        if (certificates == null || certificates.length == 0) {
            return null;
        }
        try {
            return java.security.MessageDigest.getInstance("SHA-256")
                    .digest(certificates[0].getEncoded());
        } catch (Exception failure) {
            throw new DomainException(ErrorCode.PLATFORM_PROOF_REQUIRED);
        }
    }

    private static byte[] headerFingerprint(HttpServletRequest request) {
        String value = request.getHeader(CLIENT_CERT_FINGERPRINT_HEADER);
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            byte[] parsed = java.util.HexFormat.of().parseHex(value.trim());
            return parsed.length == 32 ? parsed : null;
        } catch (IllegalArgumentException failure) {
            return null;
        }
    }

    private static byte[] headerSecret(HttpServletRequest request) {
        String value = request.getHeader(PROXY_SECRET_HEADER);
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Base64.getDecoder().decode(value.trim());
        } catch (IllegalArgumentException failure) {
            return null;
        }
    }

    private record Policy(
            String mode, Set<String> fingerprints, byte[] secret, List<CidrRange> proxyCidrs) {
        private Policy {
            secret = secret == null ? null : secret.clone();
        }
    }
}
