package local.ptmcp.persistence.security;

import local.ptmcp.common.error.DomainException;
import local.ptmcp.common.error.ErrorCode;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * One authoritatively consistent security state read from the primary metadata database. {@code
 * checkedAt} is the conservative moment before the read transaction started; it is never advanced
 * by cache hits, heartbeats or clock drift and is the only basis for the 5-second staleness rule.
 *
 * <p>{@code subjectVersions} maps a user id to its live auth-version signal (0 when the subject has
 * no active credential state); {@code familyVersions} covers only ACTIVE refresh families, so a
 * revoked family simply disappears and every version comparison against it fails closed.
 */
public record SecuritySnapshot(
        long policyEpoch,
        Map<Long, Long> subjectVersions,
        long platformVersion,
        Map<UUID, Long> familyVersions,
        Instant checkedAt) {
    public SecuritySnapshot {
        if (policyEpoch <= 0 || platformVersion <= 0 || checkedAt == null) {
            throw new DomainException(ErrorCode.INVALID_ARGUMENT);
        }
        subjectVersions = normalizedSubjects(subjectVersions);
        familyVersions = normalizedFamilies(familyVersions);
        checkedAt = checkedAt.truncatedTo(java.time.temporal.ChronoUnit.MICROS);
    }

    /** True when a new query may still rely on this snapshot: at most 5 seconds old. */
    public boolean isFresh(Instant now) {
        if (now == null) {
            throw new DomainException(ErrorCode.INVALID_ARGUMENT);
        }
        return !now.isBefore(checkedAt) && !now.isAfter(checkedAt.plus(Duration.ofSeconds(5)));
    }

    /**
     * State equality ignoring the read time: a differing epoch, platform version, subject version
     * or family version means the earlier snapshot is invalidated and the caller must re-decide.
     */
    public boolean sameState(SecuritySnapshot other) {
        if (other == null) {
            return false;
        }
        return policyEpoch == other.policyEpoch
                && platformVersion == other.platformVersion
                && subjectVersions.equals(other.subjectVersions)
                && familyVersions.equals(other.familyVersions);
    }

    private static Map<Long, Long> normalizedSubjects(Map<Long, Long> versions) {
        Map<Long, Long> source = versions == null ? Map.of() : versions;
        for (Map.Entry<Long, Long> entry : source.entrySet()) {
            if (entry.getKey() == null
                    || entry.getKey() <= 0
                    || entry.getValue() == null
                    || entry.getValue() < 0) {
                throw new DomainException(ErrorCode.INVALID_ARGUMENT);
            }
        }
        return Map.copyOf(source);
    }

    private static Map<UUID, Long> normalizedFamilies(Map<UUID, Long> versions) {
        Map<UUID, Long> source = versions == null ? Map.of() : versions;
        for (Map.Entry<UUID, Long> entry : source.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null || entry.getValue() <= 0) {
                throw new DomainException(ErrorCode.INVALID_ARGUMENT);
            }
        }
        return Map.copyOf(source);
    }
}
