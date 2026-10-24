package local.ptmcp.persistence.schema;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

/**
 * Normalized schema capture handed to the staging store. Values come from built-in catalog queries
 * only; identifiers and comments are data and never interpreted as instructions.
 *
 * @param database captured database name (MySQL: DATABASE(), PG: current_database())
 */
public record SnapshotModel(
        String database, String lowerCaseIdentifiers, List<NamespaceModel> namespaces) {

    public static final int MAX_NAMESPACES = 512;
    public static final int MAX_OBJECTS_PER_NAMESPACE = 5000;
    public static final int MAX_COLUMNS_PER_OBJECT = 512;
    public static final int MAX_IDENTIFIER_CHARS = 128;

    public SnapshotModel {
        if (database == null
                || database.isBlank()
                || database.length() > MAX_IDENTIFIER_CHARS
                || database.indexOf('\u0000') >= 0) {
            throw new IllegalArgumentException("database");
        }
        if (lowerCaseIdentifiers == null || lowerCaseIdentifiers.isBlank()) {
            throw new IllegalArgumentException("lowerCaseIdentifiers");
        }
        if (namespaces == null || namespaces.isEmpty() || namespaces.size() > MAX_NAMESPACES) {
            throw new IllegalArgumentException("namespaces");
        }
        namespaces = List.copyOf(namespaces);
    }

    /** One catalog/schema bucket. MySQL keeps {@code schema} empty; PG keeps catalog empty. */
    public record NamespaceModel(
            String catalogRaw,
            String catalogNorm,
            String schemaRaw,
            String schemaNorm,
            List<ObjectModel> objects) {

        public NamespaceModel {
            if (catalogRaw == null || catalogRaw.length() > MAX_IDENTIFIER_CHARS) {
                throw new IllegalArgumentException("catalogRaw");
            }
            if (catalogNorm == null
                    || catalogNorm.length() > MAX_IDENTIFIER_CHARS
                    || (catalogNorm.isBlank() && !catalogRaw.isEmpty())) {
                throw new IllegalArgumentException("catalogNorm");
            }
            if (schemaRaw == null || schemaRaw.length() > MAX_IDENTIFIER_CHARS) {
                throw new IllegalArgumentException("schemaRaw");
            }
            if (schemaNorm == null
                    || schemaNorm.length() > MAX_IDENTIFIER_CHARS
                    || (schemaNorm.isBlank() && !schemaRaw.isEmpty())) {
                throw new IllegalArgumentException("schemaNorm");
            }
            if (objects == null
                    || objects.isEmpty()
                    || objects.size() > MAX_OBJECTS_PER_NAMESPACE) {
                throw new IllegalArgumentException("objects");
            }
            objects = List.copyOf(objects);
        }
    }

    public record ObjectModel(
            String objectRaw,
            String objectNorm,
            boolean quoted,
            String kind,
            byte[] definitionHash,
            String physicalIdentity,
            String tableComment,
            List<ColumnModel> columns) {

        public static final String KIND_TABLE = "TABLE";
        public static final String KIND_VIEW = "VIEW";

        public ObjectModel {
            if (objectRaw == null
                    || objectRaw.isBlank()
                    || objectRaw.length() > MAX_IDENTIFIER_CHARS
                    || objectRaw.indexOf('\u0000') >= 0) {
                throw new IllegalArgumentException("objectRaw");
            }
            if (objectNorm == null
                    || objectNorm.isBlank()
                    || objectNorm.length() > MAX_IDENTIFIER_CHARS) {
                throw new IllegalArgumentException("objectNorm");
            }
            if (!KIND_TABLE.equals(kind) && !KIND_VIEW.equals(kind)) {
                throw new IllegalArgumentException("kind");
            }
            if (definitionHash == null || definitionHash.length != 32) {
                throw new IllegalArgumentException("definitionHash");
            }
            if (physicalIdentity != null && physicalIdentity.length() > 256) {
                throw new IllegalArgumentException("physicalIdentity");
            }
            if (tableComment != null && tableComment.indexOf('\u0000') >= 0) {
                throw new IllegalArgumentException("tableComment");
            }
            if (columns == null || columns.isEmpty() || columns.size() > MAX_COLUMNS_PER_OBJECT) {
                throw new IllegalArgumentException("columns");
            }
            definitionHash = definitionHash.clone();
            columns = List.copyOf(columns);
        }
    }

    public record ColumnModel(
            int ordinal,
            String columnRaw,
            String columnNorm,
            boolean quoted,
            String dataType,
            int jdbcType,
            boolean nullable,
            Integer precision,
            Integer scale,
            Long maxLength,
            String comment,
            boolean primaryKey,
            String foreignKeyJson) {

        public ColumnModel {
            if (ordinal < 0) {
                throw new IllegalArgumentException("ordinal");
            }
            if (columnRaw == null
                    || columnRaw.isBlank()
                    || columnRaw.length() > MAX_IDENTIFIER_CHARS
                    || columnRaw.indexOf('\u0000') >= 0) {
                throw new IllegalArgumentException("columnRaw");
            }
            if (columnNorm == null
                    || columnNorm.isBlank()
                    || columnNorm.length() > MAX_IDENTIFIER_CHARS) {
                throw new IllegalArgumentException("columnNorm");
            }
            if (dataType == null || dataType.isBlank() || dataType.length() > 128) {
                throw new IllegalArgumentException("dataType");
            }
            if (comment != null && comment.indexOf('\u0000') >= 0) {
                throw new IllegalArgumentException("comment");
            }
        }
    }

    /**
     * SHA-256 over a canonical text projection; stored as-is in {@code schema_snapshot.checksum}.
     */
    public byte[] checksum() {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
        StringBuilder text = new StringBuilder(256);
        text.append(database).append('\n').append(lowerCaseIdentifiers).append('\n');
        for (NamespaceModel namespace : namespaces) {
            text.append(namespace.catalogNorm())
                    .append('.')
                    .append(namespace.schemaNorm())
                    .append('\n');
            for (ObjectModel object : namespace.objects()) {
                text.append(object.objectNorm())
                        .append('/')
                        .append(object.kind())
                        .append('/')
                        .append(HexFormat.of().formatHex(object.definitionHash()))
                        .append('\n');
                for (ColumnModel column : object.columns()) {
                    text.append(column.columnNorm())
                            .append('#')
                            .append(column.ordinal())
                            .append('#')
                            .append(column.dataType())
                            .append('#')
                            .append(column.jdbcType())
                            .append('#')
                            .append(column.nullable())
                            .append('\n');
                }
            }
        }
        return digest.digest(text.toString().getBytes(StandardCharsets.UTF_8));
    }
}
