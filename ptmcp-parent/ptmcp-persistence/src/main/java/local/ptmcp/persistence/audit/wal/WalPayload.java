package local.ptmcp.persistence.audit.wal;

import local.ptmcp.persistence.audit.AuditTerminal;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;

/**
 * Versioned binary encoding of the terminal events a WAL frame carries. The payload is opaque to
 * the frame layer: it is hashed and encrypted, and decoding is the replayer's explicit,
 * schema-version-checked step. Unknown versions are quarantined, never "best effort" parsed.
 *
 * <p>The encoding is length-prefixed little-endian base 128 varints plus raw UTF-8 fields; it is
 * not SQL, not JSON and never a Java deserialization entry point.
 */
public final class WalPayload {
    public static final int SCHEMA_VERSION = 1;

    private WalPayload() {}

    public static byte[] encode(AuditTerminal terminal) {
        Objects.requireNonNull(terminal, "terminal");
        Buffer out = new Buffer(256);
        out.putVarint(SCHEMA_VERSION);
        out.putFixed(terminal.requestId());
        out.putVarint(ordinal(terminal.result()));
        out.putVarint(ordinal(terminal.verdict()));
        out.putString(terminal.errorCode());
        out.putOptionalLong(terminal.durationMs());
        out.putOptionalInt(terminal.queueMs());
        out.putOptionalInt(terminal.rowsReturned());
        out.putBoolean(terminal.truncated());
        out.putString(terminal.maskedSql());
        out.putOptionalBytes(terminal.sqlFingerprint());
        out.putOptionalBytes(terminal.encryptedRaw());
        out.putString(terminal.keyId());
        out.putString(terminal.tool());
        out.putOptionalLong(terminal.userId());
        out.putOptionalLong(terminal.platformId());
        out.putOptionalLong(terminal.datasourceId());
        out.putFixed(terminal.startedAt());
        return out.toArray();
    }

    public static AuditTerminal decode(int schemaVersion, byte[] bytes) {
        if (schemaVersion != SCHEMA_VERSION) {
            throw new IllegalArgumentException("unsupported WAL payload schema " + schemaVersion);
        }
        Reader in = new Reader(bytes);
        int version = (int) in.varint();
        if (version != SCHEMA_VERSION) {
            throw new IllegalArgumentException("unsupported WAL payload schema " + version);
        }
        UUID requestId = in.fixed();
        AuditTerminal.Result result = AuditTerminal.Result.values()[(int) in.varint()];
        AuditTerminal.Verdict verdict = AuditTerminal.Verdict.values()[(int) in.varint()];
        String errorCode = in.string();
        Long durationMs = in.optionalLong();
        Integer queueMs = in.optionalInt();
        Integer rowsReturned = in.optionalInt();
        boolean truncated = in.booleanValue();
        String maskedSql = in.string();
        byte[] sqlFingerprint = in.optionalBytes();
        byte[] encryptedRaw = in.optionalBytes();
        String keyId = in.string();
        String tool = in.string();
        Long userId = in.optionalLong();
        Long platformId = in.optionalLong();
        Long datasourceId = in.optionalLong();
        java.time.Instant startedAt = in.fixedInstant();
        return new AuditTerminal(
                requestId,
                result,
                verdict,
                errorCode,
                durationMs,
                queueMs,
                rowsReturned,
                truncated,
                maskedSql,
                sqlFingerprint,
                encryptedRaw,
                keyId,
                tool,
                userId,
                platformId,
                datasourceId,
                startedAt);
    }

    public static byte[] sha256(byte[] bytes) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(bytes);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    private static int ordinal(Enum<?> value) {
        return value == null ? -1 : value.ordinal();
    }

    static final class Buffer {
        private byte[] data;
        private int length;

        Buffer(int capacity) {
            data = new byte[capacity];
        }

        void putVarint(long value) {
            long rest = value;
            while (true) {
                if ((rest & ~0x7F) == 0) {
                    putByte((byte) rest);
                    return;
                }
                putByte((byte) ((rest & 0x7F) | 0x80));
                rest >>>= 7;
            }
        }

        void putBoolean(boolean value) {
            putByte((byte) (value ? 1 : 0));
        }

        void putOptionalLong(Long value) {
            if (value == null) {
                putBoolean(false);
            } else {
                putBoolean(true);
                putVarint(value);
            }
        }

        void putOptionalInt(Integer value) {
            if (value == null) {
                putBoolean(false);
            } else {
                putBoolean(true);
                putVarint(value);
            }
        }

        void putString(String value) {
            if (value == null) {
                putVarint(-1);
                return;
            }
            byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
            putVarint(bytes.length);
            put(bytes);
        }

        void putOptionalBytes(byte[] value) {
            if (value == null) {
                putVarint(-1);
                return;
            }
            putVarint(value.length);
            put(value);
        }

        void putFixed(UUID value) {
            putLong(value.getMostSignificantBits());
            putLong(value.getLeastSignificantBits());
        }

        void putFixed(java.time.Instant value) {
            putVarint(value.getEpochSecond());
            putVarint(value.getNano());
        }

        private void putLong(long value) {
            for (int shift = 56; shift >= 0; shift -= 8) {
                putByte((byte) (value >>> shift));
            }
        }

        private void put(byte[] bytes) {
            ensure(length + bytes.length);
            System.arraycopy(bytes, 0, data, length, bytes.length);
            length += bytes.length;
        }

        private void putByte(byte value) {
            ensure(length + 1);
            data[length++] = value;
        }

        private void ensure(int size) {
            if (size > data.length) {
                data = Arrays.copyOf(data, Math.max(size, data.length * 2));
            }
        }

        byte[] toArray() {
            return Arrays.copyOf(data, length);
        }
    }

    static final class Reader {
        private final byte[] data;
        private int position;

        Reader(byte[] data) {
            this.data = Objects.requireNonNull(data);
        }

        long varint() {
            long value = 0;
            int shift = 0;
            while (true) {
                if (position >= data.length) {
                    throw new IllegalArgumentException("truncated varint");
                }
                byte current = data[position++];
                value |= (long) (current & 0x7F) << shift;
                if ((current & 0x80) == 0) {
                    return value;
                }
                shift += 7;
                if (shift > 63) {
                    throw new IllegalArgumentException("varint overflow");
                }
            }
        }

        boolean booleanValue() {
            return varint() != 0;
        }

        Long optionalLong() {
            return varint() != 0 ? varint() : null;
        }

        Integer optionalInt() {
            return varint() != 0 ? (int) varint() : null;
        }

        String string() {
            long mark = varint();
            if (mark < 0) {
                return null;
            }
            int size = (int) mark;
            if (position + size > data.length) {
                throw new IllegalArgumentException("truncated string");
            }
            String value = new String(data, position, size, StandardCharsets.UTF_8);
            position += size;
            return value;
        }

        byte[] optionalBytes() {
            long mark = varint();
            if (mark < 0) {
                return null;
            }
            int size = (int) mark;
            if (position + size > data.length) {
                throw new IllegalArgumentException("truncated bytes");
            }
            byte[] value = Arrays.copyOfRange(data, position, position + size);
            position += size;
            return value;
        }

        UUID fixed() {
            long msb = 0;
            long lsb = 0;
            for (int shift = 56; shift >= 0; shift -= 8) {
                msb |= (long) (data[position++] & 0xFF) << shift;
            }
            for (int shift = 56; shift >= 0; shift -= 8) {
                lsb |= (long) (data[position++] & 0xFF) << shift;
            }
            return new UUID(msb, lsb);
        }

        java.time.Instant fixedInstant() {
            return java.time.Instant.ofEpochSecond(varint(), varint());
        }
    }
}
