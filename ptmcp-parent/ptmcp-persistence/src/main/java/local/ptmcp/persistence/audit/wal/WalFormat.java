package local.ptmcp.persistence.audit.wal;

import local.ptmcp.common.error.DomainException;
import local.ptmcp.common.error.ErrorCode;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.UUID;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Wire format of one encrypted WAL frame (schema 1):
 *
 * <pre>[int totalLen][byte schemaVersion][u8 kind][16 eventId][16 requestId][int seq]
 * [u8 keyId][32 payloadHash][12 nonce][int cipherLen][cipher]</pre>
 *
 * <p>The GCM AAD covers every header byte, so a header tampered in any way fails the tag check.
 * {@code payloadHash} is SHA-256 over the plaintext and is verified again after decryption. The
 * nonce is random per frame: keys are 256-bit AES-GCM data keys unique to a segment lifetime, and
 * the frame count stays far below the 2^32 random-nonce collision comfort zone.
 */
final class WalFormat {
    static final byte SCHEMA_VERSION = 1;
    static final byte[] MAGIC = {'P', 'T', 'M', 'W', 'A', 'L', 0, 1};
    static final int NONCE_BYTES = 12;
    static final int HASH_BYTES = 32;
    static final int TAG_BITS = 128;
    static final String KIND_TERMINAL = "TERMINAL";

    private static final SecureRandom RANDOM = new SecureRandom();

    private WalFormat() {}

    /** One decrypted, verified frame. The payload bytes are owned by the caller after this. */
    record Frame(String eventKind, UUID eventId, UUID requestId, int seq, byte[] payload) {}

    /** Plaintext segment identity written once at the start of every segment file. */
    record SegmentHeader(
            UUID segmentId,
            String gatewayInstanceId,
            String bootId,
            java.time.Instant createdAt,
            String keyId) {}

    /**
     * Reads the plaintext segment header, leaving {@code buffer} positioned at the first frame.
     * Anything that fails the magic or schema check quarantines the whole segment.
     */
    static SegmentHeader decodeSegmentHeader(ByteBuffer buffer) throws CorruptFrameException {
        try {
            byte[] magic = new byte[MAGIC.length];
            buffer.get(magic);
            if (!Arrays.equals(magic, MAGIC)) {
                throw new CorruptFrameException("segment magic mismatch");
            }
            byte version = buffer.get();
            if (version != SCHEMA_VERSION) {
                throw new CorruptFrameException("unsupported segment schema " + version);
            }
            UUID segmentId = new UUID(buffer.getLong(), buffer.getLong());
            String instance = new String(shortString(buffer), StandardCharsets.UTF_8);
            String boot = new String(shortString(buffer), StandardCharsets.UTF_8);
            java.time.Instant createdAt = java.time.Instant.ofEpochMilli(buffer.getLong());
            String keyId = new String(shortString(buffer), StandardCharsets.UTF_8);
            return new SegmentHeader(segmentId, instance, boot, createdAt, keyId);
        } catch (CorruptFrameException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw new CorruptFrameException("malformed segment header", failure);
        }
    }

    static byte[] newNonce() {
        byte[] nonce = new byte[NONCE_BYTES];
        RANDOM.nextBytes(nonce);
        return nonce;
    }

    /**
     * Encodes and encrypts one frame; the returned bytes are the exact unit that must reach the
     * channel before fsync. Returns the cipher output for callers that split writes.
     */
    static byte[] encodeFrame(Frame frame, WalKey key, byte[] nonce) {
        byte[] keyId = key.keyId().getBytes(StandardCharsets.UTF_8);
        byte[] kind = frame.eventKind().getBytes(StandardCharsets.UTF_8);
        if (kind.length > 255 || keyId.length > 255) {
            throw new DomainException(ErrorCode.INVALID_ARGUMENT);
        }
        byte[] hash = WalPayload.sha256(frame.payload());
        byte[] header = header(kind, frame, keyId, hash, nonce);
        byte[] cipher = encrypt(frame.payload(), key, nonce, header);
        int total = header.length + 4 + cipher.length;
        ByteBuffer out = ByteBuffer.allocate(4 + total);
        out.putInt(total);
        out.put(header);
        out.putInt(cipher.length);
        out.put(cipher);
        return out.array();
    }

    private static byte[] header(
            byte[] kind, Frame frame, byte[] keyId, byte[] hash, byte[] nonce) {
        int length =
                1 + 1 + kind.length + 16 + 16 + 4 + 1 + keyId.length + HASH_BYTES + NONCE_BYTES;
        ByteBuffer header = ByteBuffer.allocate(length);
        header.put(SCHEMA_VERSION);
        header.put((byte) kind.length);
        header.put(kind);
        header.putLong(frame.eventId().getMostSignificantBits());
        header.putLong(frame.eventId().getLeastSignificantBits());
        header.putLong(frame.requestId().getMostSignificantBits());
        header.putLong(frame.requestId().getLeastSignificantBits());
        header.putInt(frame.seq());
        header.put((byte) keyId.length);
        header.put(keyId);
        header.put(hash);
        header.put(nonce);
        return header.array();
    }

    private static byte[] encrypt(byte[] plaintext, WalKey key, byte[] nonce, byte[] aad) {
        Cipher cipher;
        try {
            cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(
                    Cipher.ENCRYPT_MODE,
                    new SecretKeySpec(key.aesKey(), "AES"),
                    new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(aad);
            return cipher.doFinal(plaintext);
        } catch (Exception error) {
            throw new DomainException(ErrorCode.AUDIT_UNAVAILABLE);
        }
    }

    /** Frame header length without the leading total-length int. */
    static int headerLength(String kind, String keyId) {
        return 1 + 1 + kind.length() + 32 + 4 + 1 + keyId.length() + HASH_BYTES + NONCE_BYTES;
    }

    /**
     * Parses and decrypts one frame from {@code buffer} (positioned at the frame start). Returns
     * the decoded frame with a fresh plaintext array, or throws {@link CorruptFrameException} when
     * any structural or cryptographic check fails. The buffer position advances past the frame.
     */
    static Frame decodeFrame(ByteBuffer buffer, java.util.function.Function<String, WalKey> keys)
            throws CorruptFrameException {
        try {
            int start = buffer.position();
            int originalLimit = buffer.limit();
            int total = buffer.getInt();
            if (total <= 0 || start + 4 + total > originalLimit) {
                throw new CorruptFrameException("frame length exceeds the buffer");
            }
            int end = start + 4 + total;
            buffer.limit(end);
            byte schemaVersion = buffer.get();
            if (schemaVersion != SCHEMA_VERSION) {
                throw new CorruptFrameException("unsupported schema " + schemaVersion);
            }
            byte[] kind = shortString(buffer);
            UUID eventId = new UUID(buffer.getLong(), buffer.getLong());
            UUID requestId = new UUID(buffer.getLong(), buffer.getLong());
            int seq = buffer.getInt();
            byte[] keyId = shortString(buffer);
            byte[] hash = new byte[HASH_BYTES];
            buffer.get(hash);
            byte[] nonce = new byte[NONCE_BYTES];
            buffer.get(nonce);
            int headerEnd = buffer.position();
            int cipherLength = buffer.getInt();
            if (cipherLength <= 0 || headerEnd + 4 + cipherLength != end) {
                throw new CorruptFrameException("cipher length mismatch");
            }
            byte[] cipher = new byte[cipherLength];
            buffer.get(cipher);
            buffer.limit(originalLimit);
            buffer.position(end);
            WalKey key = keys.apply(new String(keyId, StandardCharsets.UTF_8));
            if (key == null) {
                throw new CorruptFrameException("unknown key id");
            }
            byte[] header =
                    Arrays.copyOfRange(
                            buffer.array(),
                            buffer.arrayOffset() + start + 4,
                            buffer.arrayOffset() + headerEnd);
            byte[] plaintext = decrypt(cipher, key, nonce, header);
            if (!java.util.Arrays.equals(hash, WalPayload.sha256(plaintext))) {
                throw new CorruptFrameException("payload hash mismatch");
            }
            return new Frame(
                    new String(kind, StandardCharsets.UTF_8), eventId, requestId, seq, plaintext);
        } catch (CorruptFrameException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw new CorruptFrameException("malformed frame", failure);
        }
    }

    private static byte[] decrypt(byte[] ciphertext, WalKey key, byte[] nonce, byte[] aad)
            throws CorruptFrameException {
        try {
            Cipher decipher = Cipher.getInstance("AES/GCM/NoPadding");
            decipher.init(
                    Cipher.DECRYPT_MODE,
                    new SecretKeySpec(key.aesKey(), "AES"),
                    new GCMParameterSpec(TAG_BITS, nonce));
            decipher.updateAAD(aad);
            return decipher.doFinal(ciphertext);
        } catch (Exception error) {
            throw new CorruptFrameException("decryption failed", error);
        }
    }

    private static byte[] shortString(ByteBuffer buffer) {
        int length = buffer.get() & 0xFF;
        byte[] value = new byte[length];
        buffer.get(value);
        return value;
    }

    static final class CorruptFrameException extends Exception {
        CorruptFrameException(String message) {
            super(message);
        }

        CorruptFrameException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
