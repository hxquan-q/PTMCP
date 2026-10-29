package local.ptmcp.gateway.mcp;

import local.ptmcp.common.error.DomainException;
import local.ptmcp.common.error.ErrorCode;
import local.ptmcp.persistence.security.SecuritySnapshot;
import local.ptmcp.persistence.security.SecurityStateReader;

import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * PAT and OAuth-JWT bearer authentication for /mcp. Every request authenticates independently; no
 * session, cookie or previous initialize is ever consulted. PATs are located by SHA-256 digest;
 * JWTs are verified against the configured remote JWKS with issuer and audience pinned by
 * deployment configuration. A declared X-Platform-ID that contradicts the credential's bound
 * platform is rejected before any tool runs; the header can never upgrade or move a platform.
 */
final class PatJwtAuthentication implements McpAuthentication {
    private static final String PAT_PREFIX = "ptmcp_pat_";
    private static final Set<String> KNOWN_SCOPES =
            Set.of("mcp:catalog", "mcp:schema", "mcp:query");

    private final IdentityStore identities;
    private final SecurityStateReader security;
    private final Tier1ProofVerifier proofVerifier;
    private final JwtDecoder jwtDecoder;
    private final String resource;

    PatJwtAuthentication(
            IdentityStore identities,
            SecurityStateReader security,
            Tier1ProofVerifier proofVerifier,
            JwtDecoder jwtDecoder,
            String resource) {
        this.identities = identities;
        this.security = security;
        this.proofVerifier = proofVerifier;
        this.jwtDecoder = jwtDecoder;
        this.resource = resource == null ? "" : resource;
    }

    @Override
    public McpIdentity authenticate(
            String bearer, jakarta.servlet.http.HttpServletRequest request) {
        McpIdentity identity =
                bearer.startsWith(PAT_PREFIX) ? authenticatePat(bearer) : authenticateJwt(bearer);
        requirePlatformHeaderConsistency(identity, request);
        proofVerifier.verify(identity, request);
        return identity;
    }

    @Override
    public McpIdentity revalidate(McpIdentity identity) {
        SecuritySnapshot snapshot =
                security.read(
                        new SecurityStateReader.SubjectRequest(
                                identity.platformId(), identity.userId(), familyIds(identity)));
        if (!snapshot.isFresh(Instant.now())) {
            throw new DomainException(ErrorCode.SECURITY_STATE_UNAVAILABLE);
        }
        requireUserVersion(snapshot, identity.userId(), identity.authVersion());
        if (!identity.expiresAt().isAfter(Instant.now())) {
            throw new DomainException(ErrorCode.TOKEN_INVALID);
        }
        if (!identities.userActive(identity.userId())) {
            throw new DomainException(ErrorCode.TOKEN_INVALID);
        }
        if (identity.credentialId().startsWith("pat:")) {
            if (!identities.patStillActive(Long.parseLong(identity.credentialId().substring(4)))) {
                throw new DomainException(ErrorCode.TOKEN_REVOKED);
            }
            return new McpIdentity(
                    identity.userId(),
                    identity.platformId(),
                    identity.authVersion(),
                    identity.credentialId(),
                    identity.clientId(),
                    identity.scopes(),
                    identity.expiresAt(),
                    snapshot.policyEpoch(),
                    snapshot.checkedAt());
        }
        UUID familyId = UUID.fromString(identity.credentialId().substring(4));
        if (!snapshot.familyVersions().containsKey(familyId)) {
            throw new DomainException(ErrorCode.TOKEN_REVOKED);
        }
        IdentityStore.FamilyRecord family =
                identities
                        .findFamily(familyId)
                        .orElseThrow(() -> new DomainException(ErrorCode.TOKEN_REVOKED));
        if (family.authVersion() != identity.authVersion()
                || family.userId() != identity.userId()
                || family.platformId() != identity.platformId()
                || !java.util.Objects.equals(family.clientId(), identity.clientId())
                || !"ACTIVE".equals(family.status())
                || !family.withinWindow()
                || !family.platformActive()
                || !family.userActive()
                || !family.clientActive()) {
            throw new DomainException(ErrorCode.TOKEN_REVOKED);
        }
        return new McpIdentity(
                identity.userId(),
                identity.platformId(),
                identity.authVersion(),
                identity.credentialId(),
                identity.clientId(),
                identity.scopes(),
                identity.expiresAt(),
                snapshot.policyEpoch(),
                snapshot.checkedAt());
    }

    private static List<UUID> familyIds(McpIdentity identity) {
        if (identity.credentialId().startsWith("jwt:")) {
            return List.of(UUID.fromString(identity.credentialId().substring(4)));
        }
        return List.of();
    }

    private McpIdentity authenticatePat(String bearer) {
        IdentityStore.PatRecord record =
                identities
                        .findPat(sha256(bearer.getBytes(StandardCharsets.UTF_8)))
                        .orElseThrow(() -> new DomainException(ErrorCode.TOKEN_INVALID));
        if ("REVOKED".equals(record.status())) {
            throw new DomainException(ErrorCode.TOKEN_REVOKED);
        }
        if (!"ACTIVE".equals(record.status())
                || record.expiresAt() == null
                || !record.expiresAt().isAfter(LocalDateTime.now(ZoneOffset.UTC))) {
            throw new DomainException(ErrorCode.TOKEN_INVALID);
        }
        if (!record.platformActive() || !record.userActive()) {
            throw new DomainException(ErrorCode.TOKEN_INVALID);
        }
        SecuritySnapshot snapshot =
                security.read(
                        new SecurityStateReader.SubjectRequest(
                                record.platformId(), record.userId(), List.of()));
        requireUserVersion(snapshot, record.userId(), record.authVersion());
        return new McpIdentity(
                record.userId(),
                record.platformId(),
                record.authVersion(),
                "pat:" + record.id(),
                null,
                parseScopes(record.scopesJson()),
                record.expiresAt().toInstant(ZoneOffset.UTC),
                snapshot.policyEpoch(),
                snapshot.checkedAt());
    }

    private McpIdentity authenticateJwt(String bearer) {
        if (jwtDecoder == null) {
            throw new DomainException(ErrorCode.TOKEN_INVALID);
        }
        Jwt jwt;
        try {
            jwt = jwtDecoder.decode(bearer);
        } catch (JwtException | IllegalArgumentException failure) {
            throw new DomainException(ErrorCode.TOKEN_INVALID);
        }
        if (jwt.getAudience() == null || !jwt.getAudience().contains(resource)) {
            throw new DomainException(ErrorCode.TOKEN_INVALID);
        }
        long userId = numericClaim(jwt, "sub");
        long platformId = numericClaim(jwt, "platform_id");
        long authVersion = numericClaim(jwt, "auth_version");
        Object sidClaim = jwt.getClaimAsString("sid");
        if (sidClaim == null) {
            throw new DomainException(ErrorCode.TOKEN_INVALID);
        }
        UUID familyId;
        try {
            familyId = UUID.fromString(sidClaim.toString());
        } catch (IllegalArgumentException invalid) {
            throw new DomainException(ErrorCode.TOKEN_INVALID);
        }
        IdentityStore.FamilyRecord family =
                identities
                        .findFamily(familyId)
                        .orElseThrow(() -> new DomainException(ErrorCode.TOKEN_REVOKED));
        if (!"ACTIVE".equals(family.status())
                || !family.withinWindow()
                || !family.platformActive()
                || !family.userActive()
                || !family.clientActive()
                || family.userId() != userId
                || family.platformId() != platformId
                || family.authVersion() != authVersion
                || !java.util.Objects.equals(
                        family.clientId(), jwt.getClaimAsString("client_id"))) {
            throw new DomainException(ErrorCode.TOKEN_REVOKED);
        }
        SecuritySnapshot snapshot =
                security.read(
                        new SecurityStateReader.SubjectRequest(
                                platformId, userId, List.of(familyId)));
        requireUserVersion(snapshot, userId, authVersion);
        return new McpIdentity(
                userId,
                platformId,
                authVersion,
                "jwt:" + familyId,
                jwt.getClaimAsString("client_id"),
                parseScopes(jwt.getClaimAsString("scope")),
                jwt.getExpiresAt(),
                snapshot.policyEpoch(),
                snapshot.checkedAt());
    }

    private static void requireUserVersion(
            SecuritySnapshot snapshot, long userId, long authVersion) {
        if (!snapshot.isFresh(Instant.now())) {
            throw new DomainException(ErrorCode.SECURITY_STATE_UNAVAILABLE);
        }
        if (authVersion <= 0
                || snapshot.subjectVersions().getOrDefault(userId, 0L) != authVersion) {
            throw new DomainException(ErrorCode.TOKEN_REVOKED);
        }
    }

    private static long numericClaim(Jwt jwt, String claim) {
        Object value = jwt.getClaim(claim);
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String text) {
            try {
                return Long.parseLong(text);
            } catch (NumberFormatException invalid) {
                // Fall through to the uniform rejection below.
            }
        }
        throw new DomainException(ErrorCode.TOKEN_INVALID);
    }

    private static Set<String> parseScopes(String json) {
        Set<String> scopes = new HashSet<>();
        if (json != null && !json.isBlank()) {
            String text = json.trim();
            if (text.startsWith("[")) {
                for (String entry : text.substring(1, text.length() - 1).split(",")) {
                    addScope(scopes, entry.replace("\"", "").trim());
                }
            } else {
                for (String entry : text.split("\\s+")) {
                    addScope(scopes, entry);
                }
            }
        }
        return scopes;
    }

    private static void addScope(Set<String> scopes, String entry) {
        if (!entry.isEmpty() && KNOWN_SCOPES.contains(entry)) {
            scopes.add(entry);
        }
    }

    private void requirePlatformHeaderConsistency(
            McpIdentity identity, jakarta.servlet.http.HttpServletRequest request) {
        String header = request.getHeader("X-Platform-ID");
        if (header == null || header.isBlank()) {
            return;
        }
        long claimed;
        try {
            claimed = Long.parseLong(header.trim());
        } catch (NumberFormatException invalid) {
            throw new DomainException(ErrorCode.PLATFORM_MISMATCH);
        }
        if (claimed != identity.platformId()) {
            throw new DomainException(ErrorCode.PLATFORM_MISMATCH);
        }
    }

    private static byte[] sha256(byte[] input) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(input);
        } catch (NoSuchAlgorithmException impossible) {
            throw new DomainException(ErrorCode.INTERNAL_ERROR);
        }
    }
}
