package local.ptmcp.admin.datasource;

import local.ptmcp.common.error.DomainException;
import local.ptmcp.common.error.ErrorCode;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Datasource management facade. Destination validation happens before any write, credentials are
 * sealed with {@link CredentialCipher} and never leave the service in plaintext, and every read DTO
 * carries the aggregate version used by If-Match.
 */
public final class DatasourceAdminService {
    private static final int MAX_CREDENTIAL = 1024;
    private static final ZoneOffset UTC = ZoneOffset.UTC;

    private final DatasourceStore store;
    private final CredentialCipher cipher;
    private final DestinationPolicy destinationPolicy;

    public DatasourceAdminService(
            DatasourceStore store, CredentialCipher cipher, DestinationPolicy destinationPolicy) {
        this.store = Objects.requireNonNull(store, "store");
        this.cipher = Objects.requireNonNull(cipher, "cipher");
        this.destinationPolicy = Objects.requireNonNull(destinationPolicy, "destinationPolicy");
    }

    public record Write(
            String code,
            String name,
            String engine,
            String host,
            Integer port,
            String databaseName,
            String username,
            String credential,
            String tlsMode,
            Integer poolMax,
            String status) {}

    public record Read(
            String id,
            String version,
            String code,
            String name,
            String engine,
            String host,
            int port,
            String databaseName,
            String username,
            String tlsMode,
            int poolMax,
            String status,
            String configVersion,
            String resourceVersion,
            String activeSnapshotId,
            Instant lastSyncAt,
            boolean credentialConfigured,
            String health,
            Instant createdAt,
            Instant updatedAt) {}

    public record Page(List<Read> items, String nextCursor) {}

    public record DeleteView(boolean deleted, String id, String version) {}

    public Read create(Write write, long actorId) {
        DatasourceStore.Fields fields = requireFields(write);
        String credential = requireCredential(write.credential());
        destinationPolicy.validate(fields.host(), fields.port());
        CredentialCipher.Sealed sealed = cipher.encrypt(credential);
        return toRead(store.create(fields, sealed.ciphertext(), sealed.keyId(), actorId));
    }

    public Read update(long id, long expectedVersion, Write write, long actorId) {
        DatasourceStore.Fields fields = requireFields(write);
        destinationPolicy.validate(fields.host(), fields.port());
        CredentialCipher.Sealed sealed =
                write.credential() == null || write.credential().isBlank()
                        ? null
                        : cipher.encrypt(requireCredential(write.credential()));
        return toRead(
                store.update(
                        id,
                        expectedVersion,
                        fields,
                        sealed == null ? null : sealed.ciphertext(),
                        sealed == null ? null : sealed.keyId(),
                        actorId));
    }

    public Read get(long id) {
        return toRead(store.get(id));
    }

    public Page list(String keyword, String engine, String status, Long afterId, int pageSize) {
        DatasourceStore.Page page = store.list(keyword, engine, status, afterId, pageSize);
        return new Page(
                page.items().stream().map(DatasourceAdminService::toRead).toList(),
                page.nextCursor() == null ? null : page.nextCursor().toString());
    }

    public DeleteView delete(long id, long expectedVersion, long actorId) {
        DatasourceStore.DeleteResult result = store.delete(id, expectedVersion, actorId);
        return new DeleteView(
                result.deleted(), Long.toString(result.id()), Long.toString(result.version()));
    }

    /**
     * Resolves the connection target for a probe. A draft may replace connectivity fields and
     * supply a one-off credential, but it can never change the datasource identity.
     */
    public DraftTarget resolveTarget(long id, Write draft) {
        DatasourceStore.Row row = store.get(id);
        if (draft == null) {
            destinationPolicy.validate(row.host(), row.port());
            return new DraftTarget(
                    row.configVersion(),
                    new DatasourceConnectionFactory.Spec(
                            row.engine(),
                            row.host(),
                            row.port(),
                            row.databaseName(),
                            row.username(),
                            row.credentialCipher(),
                            row.keyId()));
        }
        if (draft.engine() != null
                && !draft.engine().isBlank()
                && !row.engine().equals(draft.engine().trim().toUpperCase(Locale.ROOT))) {
            throw new DomainException(ErrorCode.INVALID_ARGUMENT);
        }
        if (draft.code() != null && !draft.code().isBlank() && !row.code().equals(draft.code())) {
            throw new DomainException(ErrorCode.INVALID_ARGUMENT);
        }
        String host = requireText(draft.host(), 253);
        int port = requirePort(draft.port());
        String databaseName = requireText(draft.databaseName(), 128);
        String username = requireText(draft.username(), 128);
        if (draft.tlsMode() != null
                && !draft.tlsMode().isBlank()
                && !"VERIFY_FULL".equals(draft.tlsMode())) {
            throw new DomainException(ErrorCode.INVALID_ARGUMENT);
        }
        destinationPolicy.validate(host, port);
        if (draft.credential() == null || draft.credential().isBlank()) {
            return new DraftTarget(
                    row.configVersion(),
                    new DatasourceConnectionFactory.Spec(
                            row.engine(),
                            host.trim().toLowerCase(Locale.ROOT),
                            port,
                            databaseName,
                            username,
                            row.credentialCipher(),
                            row.keyId()));
        }
        CredentialCipher.Sealed sealed = cipher.encrypt(requireCredential(draft.credential()));
        return new DraftTarget(
                row.configVersion(),
                new DatasourceConnectionFactory.Spec(
                        row.engine(),
                        host.trim().toLowerCase(Locale.ROOT),
                        port,
                        databaseName,
                        username,
                        sealed.ciphertext(),
                        sealed.keyId()));
    }

    public record DraftTarget(long configVersion, DatasourceConnectionFactory.Spec spec) {}

    private static DatasourceStore.Fields requireFields(Write write) {
        if (write == null) {
            throw new DomainException(ErrorCode.INVALID_ARGUMENT);
        }
        int port = requirePort(write.port());
        if (write.poolMax() == null) {
            throw new DomainException(ErrorCode.INVALID_ARGUMENT);
        }
        return new DatasourceStore.Fields(
                requireText(write.code(), 64),
                requireText(write.name(), 128),
                requireText(write.engine(), 16).toUpperCase(Locale.ROOT),
                requireText(write.host(), 253).trim().toLowerCase(Locale.ROOT),
                port,
                requireText(write.databaseName(), 128),
                requireText(write.username(), 128),
                requireText(write.tlsMode(), 16),
                write.poolMax(),
                requireText(write.status(), 16).toUpperCase(Locale.ROOT));
    }

    private static String requireCredential(String credential) {
        if (credential == null || credential.isBlank() || credential.length() > MAX_CREDENTIAL) {
            throw new DomainException(ErrorCode.INVALID_ARGUMENT);
        }
        return credential;
    }

    private static int requirePort(Integer port) {
        if (port == null || port < 1 || port > 65535) {
            throw new DomainException(ErrorCode.INVALID_ARGUMENT);
        }
        return port;
    }

    private static String requireText(String value, int max) {
        if (value == null || value.isBlank() || value.length() > max) {
            throw new DomainException(ErrorCode.INVALID_ARGUMENT);
        }
        return value;
    }

    private static Read toRead(DatasourceStore.Row row) {
        return new Read(
                Long.toString(row.id()),
                Long.toString(row.configVersion()),
                row.code(),
                row.name(),
                row.engine(),
                row.host(),
                row.port(),
                row.databaseName(),
                row.username(),
                row.tlsMode(),
                row.poolMax(),
                row.status(),
                Long.toString(row.configVersion()),
                Long.toString(row.resourceVersion()),
                row.activeSnapshotId() == null ? null : Long.toString(row.activeSnapshotId()),
                toInstant(row.lastSyncAt()),
                true,
                "UNKNOWN",
                toInstant(row.createdAt()),
                toInstant(row.updatedAt()));
    }

    private static Instant toInstant(LocalDateTime value) {
        return value == null ? null : value.toInstant(UTC);
    }
}
