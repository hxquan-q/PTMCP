package local.ptmcp.persistence;

import local.ptmcp.common.error.DomainException;
import local.ptmcp.common.error.ErrorCode;

import java.util.UUID;

/** Internal ID/version-only event. No free-form producer payload is accepted. */
public record SecurityEvent(UUID eventId, Kind kind, AggregateId aggregateId, long version) {
    public SecurityEvent {
        if (eventId == null
                || kind == null
                || aggregateId == null
                || version <= 0
                || kind.uuidId != (aggregateId instanceof UuidId)) {
            throw new DomainException(ErrorCode.INVALID_ARGUMENT);
        }
    }

    String payload() {
        // Both ID implementations emit only canonical ASCII digits/UUIDs, not arbitrary strings.
        return "{\"schemaVersion\":1,\"aggregateId\":\""
                + aggregateId.text()
                + "\",\"version\":"
                + version
                + "}";
    }

    public enum Kind {
        USER_SECURITY_CHANGED("USER", false),
        DEPARTMENT_CHANGED("DEPARTMENT", false),
        DELEGATION_CHANGED("ADMIN_DELEGATION", false),
        ACL_CHANGED("ACL", false),
        REFRESH_FAMILY_CHANGED("REFRESH_FAMILY", true);

        final String aggregateType;
        final boolean uuidId;

        Kind(String aggregateType, boolean uuidId) {
            this.aggregateType = aggregateType;
            this.uuidId = uuidId;
        }
    }

    public sealed interface AggregateId permits NumericId, UuidId {
        String text();
    }

    public record NumericId(long value) implements AggregateId {
        public NumericId {
            if (value <= 0) {
                throw new DomainException(ErrorCode.INVALID_ARGUMENT);
            }
        }

        @Override
        public String text() {
            return Long.toString(value);
        }
    }

    public record UuidId(UUID value) implements AggregateId {
        public UuidId {
            if (value == null) {
                throw new DomainException(ErrorCode.INVALID_ARGUMENT);
            }
        }

        @Override
        public String text() {
            return value.toString();
        }
    }
}
