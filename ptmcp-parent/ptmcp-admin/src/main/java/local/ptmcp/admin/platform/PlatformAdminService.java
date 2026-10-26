package local.ptmcp.admin.platform;

import local.ptmcp.admin.datasource.CredentialCipher;
import local.ptmcp.common.error.DomainException;
import local.ptmcp.common.error.ErrorCode;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

import javax.sql.DataSource;

/**
 * Platform registry mutations for the admin console. Proof configuration is write-only: the
 * deployment cipher seals it, storage keeps ciphertext plus key id, and no read path returns it —
 * the projection only reports whether it is configured. Tier 1 cannot run without per-call proof, a
 * proof-mode change requires fresh proof material (or a move to NONE), and every mutation lands
 * with one admin_operation_log row inside the same transaction.
 */
public final class PlatformAdminService {
    private static final Pattern CODE = Pattern.compile("^[a-zA-Z][a-zA-Z0-9_-]{0,63}$");
    private static final Pattern FINGERPRINT = Pattern.compile("^(?:[a-fA-F0-9]{2}:?){32}$");
    private static final Set<String> CATALOG_MODES = Set.of("DISCOVER", "HIDE");
    private static final Set<String> STATUSES = Set.of("ACTIVE", "DISABLED");

    private final DataSource primary;
    private final CredentialCipher cipher;

    public PlatformAdminService(DataSource primary, CredentialCipher cipher) {
        this.primary = Objects.requireNonNull(primary, "primary");
        this.cipher = Objects.requireNonNull(cipher, "cipher");
    }

    public record Write(
            String code,
            String name,
            Integer tier,
            String catalogMode,
            String proofMode,
            String status,
            ProofConfig proofConfig) {}

    public record ProofConfig(String certificateFingerprint, String proxyNetwork, String secret) {}

    /** Re-exported directory view so the controller keeps a single response shape. */
    public record PlatformView(
            String id,
            String code,
            String name,
            int tier,
            String status,
            String catalogMode,
            String proofMode,
            boolean proofConfigured,
            String scopeVersion,
            String securityVersion,
            String version) {}

    public PlatformView create(Write write, long actorId, String requestId) {
        requireActor(actorId);
        Fields fields = requireFields(write, false);
        if (write.status() == null || !STATUSES.contains(write.status())) {
            throw new DomainException(ErrorCode.INVALID_ARGUMENT);
        }
        SealedConfig sealed = sealConfig(fields.proofMode(), write.proofConfig());
        try (Connection connection = tx()) {
            try {
                requireCodeFree(connection, fields.code());
                long id =
                        insertPlatform(
                                connection, fields, write.status(), sealed, actorId, requestId);
                connection.commit();
                return toView(new PlatformDirectoryService(primary).byId(id));
            } catch (SQLException | RuntimeException failure) {
                connection.rollback();
                throw failure;
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (SQLException error) {
            throw new DomainException(ErrorCode.DEPENDENCY_UNAVAILABLE);
        }
    }

    public PlatformView get(long id) {
        PlatformDirectoryService.PlatformView view = directoryById(id);
        if (view == null) {
            throw new DomainException(ErrorCode.RESOURCE_NOT_FOUND);
        }
        return toView(view);
    }

    public PlatformView update(
            long id, long expectedVersion, Write write, long actorId, String requestId) {
        requireActor(actorId);
        if (expectedVersion <= 0) {
            throw new DomainException(ErrorCode.INVALID_ARGUMENT);
        }
        Fields fields = requireFields(write, true);
        if (write.status() == null || !STATUSES.contains(write.status())) {
            throw new DomainException(ErrorCode.INVALID_ARGUMENT);
        }
        try (Connection connection = tx()) {
            try {
                Current current = current(connection, id);
                if (!current.code().equals(fields.code())) {
                    // code is the immutable cross-system identity of a platform.
                    throw new DomainException(ErrorCode.UNSUPPORTED_FIELD);
                }
                ProofMaterial material = nextProofMaterial(current, fields, write.proofConfig());
                int updated;
                if (material.replace()) {
                    try (PreparedStatement statement =
                            statement(
                                    connection,
                                    "UPDATE platform_registry SET name = ?, tier = ?, status = ?,"
                                            + " catalog_mode = ?, proof_mode = ?, proof_config_cipher = ?,"
                                            + " proof_key_id = ?, security_version = security_version + 1,"
                                            + " update_time = UTC_TIMESTAMP(6),"
                                            + " updated_by = ? WHERE id = ? AND security_version = ?"
                                            + " AND status <> 'DELETED'")) {
                        bindCore(statement, 1, fields, write.status());
                        statement.setBytes(6, material.sealed().ciphertext());
                        statement.setString(7, material.sealed().keyId());
                        statement.setLong(8, actorId);
                        statement.setLong(9, id);
                        statement.setLong(10, expectedVersion);
                        updated = statement.executeUpdate();
                    }
                } else if (material.clear()) {
                    try (PreparedStatement statement =
                            statement(
                                    connection,
                                    "UPDATE platform_registry SET name = ?, tier = ?, status = ?,"
                                            + " catalog_mode = ?, proof_mode = ?, proof_config_cipher = NULL,"
                                            + " proof_key_id = NULL, security_version = security_version + 1,"
                                            + " update_time = UTC_TIMESTAMP(6),"
                                            + " updated_by = ? WHERE id = ? AND security_version = ?"
                                            + " AND status <> 'DELETED'")) {
                        bindCore(statement, 1, fields, write.status());
                        statement.setLong(6, actorId);
                        statement.setLong(7, id);
                        statement.setLong(8, expectedVersion);
                        updated = statement.executeUpdate();
                    }
                } else {
                    try (PreparedStatement statement =
                            statement(
                                    connection,
                                    "UPDATE platform_registry SET name = ?, tier = ?, status = ?,"
                                            + " catalog_mode = ?, proof_mode = ?,"
                                            + " security_version = security_version + 1,"
                                            + " update_time = UTC_TIMESTAMP(6),"
                                            + " updated_by = ? WHERE id = ? AND security_version = ?"
                                            + " AND status <> 'DELETED'")) {
                        bindCore(statement, 1, fields, write.status());
                        statement.setLong(6, actorId);
                        statement.setLong(7, id);
                        statement.setLong(8, expectedVersion);
                        updated = statement.executeUpdate();
                    }
                }
                if (updated != 1) {
                    throw new DomainException(ErrorCode.VERSION_CONFLICT);
                }
                log(
                        connection,
                        actorId,
                        requestId,
                        "PLATFORM_UPDATE",
                        id,
                        summary(current.fields(), current.status(), current.proofConfigured()),
                        summary(fields, write.status(), material.resultingConfigured(current)));
                connection.commit();
                return get(id);
            } catch (SQLException | RuntimeException failure) {
                connection.rollback();
                throw failure;
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (SQLException error) {
            throw new DomainException(ErrorCode.DEPENDENCY_UNAVAILABLE);
        }
    }

    /** Logical delete: rows become DELETED, leave the directory and keep the audit history. */
    public PlatformView delete(long id, long expectedVersion, long actorId, String requestId) {
        requireActor(actorId);
        if (expectedVersion <= 0) {
            throw new DomainException(ErrorCode.INVALID_ARGUMENT);
        }
        try (Connection connection = tx()) {
            try {
                Current current = current(connection, id);
                int updated;
                try (PreparedStatement statement =
                        statement(
                                connection,
                                "UPDATE platform_registry SET status = 'DELETED',"
                                        + " security_version = security_version + 1,"
                                        + " update_time = UTC_TIMESTAMP(6),"
                                        + " updated_by = ? WHERE id = ? AND security_version = ?"
                                        + " AND status <> 'DELETED'")) {
                    statement.setLong(1, actorId);
                    statement.setLong(2, id);
                    statement.setLong(3, expectedVersion);
                    updated = statement.executeUpdate();
                }
                if (updated != 1) {
                    throw new DomainException(ErrorCode.VERSION_CONFLICT);
                }
                log(
                        connection,
                        actorId,
                        requestId,
                        "PLATFORM_DELETE",
                        id,
                        summary(current.fields(), current.status(), current.proofConfigured()),
                        null);
                connection.commit();
                return new PlatformView(
                        Long.toString(id),
                        current.code(),
                        current.name(),
                        current.tier(),
                        "DELETED",
                        current.catalogMode(),
                        current.proofMode(),
                        current.proofConfigured(),
                        Long.toString(current.scopeVersion()),
                        Long.toString(current.version() + 1),
                        Long.toString(current.version() + 1));
            } catch (SQLException | RuntimeException failure) {
                connection.rollback();
                throw failure;
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (SQLException error) {
            throw new DomainException(ErrorCode.DEPENDENCY_UNAVAILABLE);
        }
    }

    // ---- validation and proof material -------------------------------------

    private static void requireActor(long actorId) {
        if (actorId <= 0) {
            throw new DomainException(ErrorCode.INVALID_ARGUMENT);
        }
    }

    private record Fields(
            String code, String name, int tier, String catalogMode, String proofMode) {}

    private record SealedConfig(byte[] ciphertext, String keyId) {}

    /** Tri-state proof outcome for an update: replace, clear, or keep the stored config. */
    private record ProofMaterial(
            SealedConfig sealed, boolean replace, boolean clear, boolean keep) {

        boolean resultingConfigured(Current current) {
            if (replace) {
                return true;
            }
            if (clear) {
                return false;
            }
            return current.proofConfigured();
        }
    }

    private Fields requireFields(Write write, boolean updating) {
        if (write == null || write.code() == null || write.name() == null) {
            throw new DomainException(ErrorCode.INVALID_ARGUMENT);
        }
        String code = write.code().trim();
        if (!updating && !CODE.matcher(code).matches()) {
            throw new DomainException(ErrorCode.INVALID_ARGUMENT);
        }
        if (updating && (code.isEmpty() || code.length() > 64)) {
            throw new DomainException(ErrorCode.INVALID_ARGUMENT);
        }
        String name = write.name().trim();
        if (name.isEmpty() || name.length() > 128) {
            throw new DomainException(ErrorCode.INVALID_ARGUMENT);
        }
        Integer tier = write.tier();
        if (tier == null || tier < 1 || tier > 3) {
            throw new DomainException(ErrorCode.INVALID_ARGUMENT);
        }
        String catalogMode = write.catalogMode();
        if (catalogMode == null || !CATALOG_MODES.contains(catalogMode)) {
            throw new DomainException(ErrorCode.INVALID_ARGUMENT);
        }
        String proofMode = write.proofMode();
        if (proofMode == null || !Set.of("MTLS", "PROXY_SECRET", "NONE").contains(proofMode)) {
            throw new DomainException(ErrorCode.INVALID_ARGUMENT);
        }
        if (tier == 1 && "NONE".equals(proofMode)) {
            throw new DomainException(ErrorCode.INVALID_ARGUMENT);
        }
        return new Fields(code, name, tier, catalogMode, proofMode);
    }

    private SealedConfig sealConfig(String proofMode, ProofConfig config) {
        if ("NONE".equals(proofMode)) {
            if (hasMaterial(config)) {
                throw new DomainException(ErrorCode.INVALID_ARGUMENT);
            }
            return null;
        }
        if ("MTLS".equals(proofMode)) {
            if (config == null
                    || config.certificateFingerprint() == null
                    || !FINGERPRINT.matcher(config.certificateFingerprint().trim()).matches()) {
                throw new DomainException(ErrorCode.INVALID_ARGUMENT);
            }
            CredentialCipher.Sealed sealed =
                    cipher.encrypt("fingerprint:" + config.certificateFingerprint().trim());
            return new SealedConfig(sealed.ciphertext(), sealed.keyId());
        }
        // PROXY_SECRET requires both the network constraint and a replaceable secret.
        if (config == null
                || config.proxyNetwork() == null
                || config.proxyNetwork().isBlank()
                || config.proxyNetwork().length() > 256
                || config.secret() == null
                || config.secret().isBlank()) {
            throw new DomainException(ErrorCode.INVALID_ARGUMENT);
        }
        CredentialCipher.Sealed sealed =
                cipher.encrypt(
                        "proxy:" + config.proxyNetwork().trim() + "\u0000" + config.secret());
        return new SealedConfig(sealed.ciphertext(), sealed.keyId());
    }

    private static boolean hasMaterial(ProofConfig config) {
        return config != null
                && ((config.certificateFingerprint() != null
                                && !config.certificateFingerprint().isBlank())
                        || (config.secret() != null && !config.secret().isBlank()));
    }

    /**
     * Proof-mode moves need fresh material (or NONE); an unchanged mode keeps the stored config
     * unless the caller supplies a replacement. Material for a different mode never silently
     * coexists with the old one.
     */
    private ProofMaterial nextProofMaterial(Current current, Fields fields, ProofConfig config) {
        boolean modeChanged = !current.proofMode().equals(fields.proofMode());
        if ("NONE".equals(fields.proofMode())) {
            if (hasMaterial(config)) {
                throw new DomainException(ErrorCode.INVALID_ARGUMENT);
            }
            if (!modeChanged && !current.proofConfigured()) {
                return new ProofMaterial(null, false, false, true);
            }
            return new ProofMaterial(null, false, current.proofConfigured() || modeChanged, false);
        }
        if (hasMaterial(config)) {
            return new ProofMaterial(sealConfig(fields.proofMode(), config), true, false, false);
        }
        if (modeChanged || !current.proofConfigured()) {
            // A new mode or a not-yet-configured platform needs fresh material to run.
            throw new DomainException(ErrorCode.INVALID_ARGUMENT);
        }
        return new ProofMaterial(null, false, false, true);
    }

    // ---- storage -----------------------------------------------------------

    private record Current(
            String code,
            String name,
            int tier,
            String status,
            String catalogMode,
            String proofMode,
            boolean proofConfigured,
            long scopeVersion,
            long version) {

        Fields fields() {
            return new Fields(code, name, tier, catalogMode, proofMode);
        }
    }

    private Current current(Connection connection, long id) throws SQLException {
        try (PreparedStatement statement =
                statement(
                        connection,
                        "SELECT code, name, tier, status, catalog_mode, proof_mode,"
                                + " proof_config_cipher IS NOT NULL, scope_version, security_version"
                                + " FROM platform_registry WHERE id = ? AND status <> 'DELETED'")) {
            statement.setLong(1, id);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new DomainException(ErrorCode.RESOURCE_NOT_FOUND);
                }
                return new Current(
                        result.getString(1),
                        result.getString(2),
                        result.getInt(3),
                        result.getString(4),
                        result.getString(5),
                        result.getString(6),
                        result.getBoolean(7),
                        result.getLong(8),
                        result.getLong(9));
            }
        }
    }

    private void requireCodeFree(Connection connection, String code) throws SQLException {
        try (PreparedStatement statement =
                statement(connection, "SELECT 1 FROM platform_registry WHERE code = ?")) {
            statement.setString(1, code);
            try (ResultSet result = statement.executeQuery()) {
                if (result.next()) {
                    throw new DomainException(ErrorCode.INVALID_ARGUMENT);
                }
            }
        }
    }

    private long insertPlatform(
            Connection connection,
            Fields fields,
            String status,
            SealedConfig sealed,
            long actorId,
            String requestId)
            throws SQLException {
        long id;
        try (PreparedStatement statement =
                connection.prepareStatement(
                        "INSERT INTO platform_registry (code, name, tier, status, catalog_mode,"
                                + " proof_mode, proof_config_cipher, proof_key_id, scope_version,"
                                + " security_version, create_time, update_time, created_by, updated_by)"
                                + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, 1, 1, UTC_TIMESTAMP(6),"
                                + " UTC_TIMESTAMP(6), ?, ?)",
                        Statement.RETURN_GENERATED_KEYS)) {
            statement.setQueryTimeout(5);
            statement.setString(1, fields.code());
            statement.setString(2, fields.name());
            statement.setInt(3, fields.tier());
            statement.setString(4, status);
            statement.setString(5, fields.catalogMode());
            statement.setString(6, fields.proofMode());
            if (sealed != null) {
                statement.setBytes(7, sealed.ciphertext());
                statement.setString(8, sealed.keyId());
            } else {
                statement.setNull(7, java.sql.Types.BLOB);
                statement.setNull(8, java.sql.Types.VARCHAR);
            }
            statement.setLong(9, actorId);
            statement.setLong(10, actorId);
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                if (!keys.next()) {
                    throw new DomainException(ErrorCode.OPERATION_UNKNOWN);
                }
                id = keys.getLong(1);
            }
        }
        log(
                connection,
                actorId,
                requestId,
                "PLATFORM_CREATE",
                id,
                null,
                summary(fields, status, sealed != null));
        return id;
    }

    private void log(
            Connection connection,
            long actorId,
            String requestId,
            String action,
            long aggregateId,
            String beforeJson,
            String afterJson)
            throws SQLException {
        try (PreparedStatement statement =
                statement(
                        connection,
                        "INSERT INTO admin_operation_log (actor_id, request_id, action,"
                                + " aggregate_type, aggregate_id, before_masked, after_masked,"
                                + " occurred_at, create_time)"
                                + " VALUES (?, ?, ?, 'PLATFORM', ?, ?, ?, UTC_TIMESTAMP(6),"
                                + " UTC_TIMESTAMP(6))")) {
            statement.setLong(1, actorId);
            statement.setString(2, requestId);
            statement.setString(3, action);
            statement.setLong(4, aggregateId);
            statement.setString(5, beforeJson);
            statement.setString(6, afterJson);
            statement.executeUpdate();
        }
    }

    private static void bindCore(
            PreparedStatement statement, int offset, Fields fields, String status)
            throws SQLException {
        statement.setString(offset, fields.name());
        statement.setInt(offset + 1, fields.tier());
        statement.setString(offset + 2, status);
        statement.setString(offset + 3, fields.catalogMode());
        statement.setString(offset + 4, fields.proofMode());
    }

    private static String summary(Fields fields, String status, boolean proofConfigured) {
        return "{\"code\":\""
                + escape(fields.code())
                + "\",\"name\":\""
                + escape(fields.name())
                + "\",\"tier\":"
                + fields.tier()
                + ",\"status\":\""
                + status
                + "\",\"catalogMode\":\""
                + fields.catalogMode()
                + "\",\"proofMode\":\""
                + fields.proofMode()
                + "\",\"proofConfigured\":"
                + proofConfigured
                + "}";
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private PlatformDirectoryService.PlatformView directoryById(long id) {
        return new PlatformDirectoryService(primary).byId(id);
    }

    private static PlatformView toView(PlatformDirectoryService.PlatformView view) {
        return new PlatformView(
                view.id(),
                view.code(),
                view.name(),
                view.tier(),
                view.status(),
                view.catalogMode(),
                view.proofMode(),
                view.proofConfigured(),
                view.scopeVersion(),
                view.securityVersion(),
                view.version());
    }

    private Connection tx() throws SQLException {
        Connection connection = primary.getConnection();
        connection.setAutoCommit(false);
        return connection;
    }

    private static PreparedStatement statement(Connection connection, String sql)
            throws SQLException {
        PreparedStatement statement = connection.prepareStatement(sql);
        statement.setQueryTimeout(5);
        return statement;
    }
}
