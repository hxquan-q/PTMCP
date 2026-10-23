package local.ptmcp.persistence;

import local.ptmcp.common.error.DomainException;
import local.ptmcp.common.error.ErrorCode;

import java.util.UUID;

/** Internal ID/version-only event. No free-form producer payload is accepted. */
record SecurityEvent(UUID eventId, Kind kind, AggregateId aggregateId, long version) {
    SecurityEvent {
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

    enum Kind {
        USER_SECURITY_CHANGED("USER", false),
        ACL_CHANGED("ACL", false),
        REFRESH_FAMILY_CHANGED("REFRESH_FAMILY", true);

        final String aggregateType;
        final boolean uuidId;

        Kind(String aggregateType, boolean uuidId) {
            this.aggregateType = aggregateType;
            this.uuidId = uuidId;
        }
    }

    sealed interface AggregateId permits NumericId, UuidId {
        String text();
    }

    record NumericId(long value) implements AggregateId {
        NumericId {
            if (value <= 0) {
                throw new DomainException(ErrorCode.INVALID_ARGUMENT);
            }
        }

        @Override
        public String text() {
            return Long.toString(value);
        }
    }

    record UuidId(UUID value) implements AggregateId {
        UuidId {
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
