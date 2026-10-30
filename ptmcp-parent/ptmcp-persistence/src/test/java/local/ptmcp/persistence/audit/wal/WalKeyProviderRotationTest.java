package local.ptmcp.persistence.audit.wal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** A rotated key ring must keep sealing with the new key and replaying frames sealed earlier. */
class WalKeyProviderRotationTest {
    private static final WalKey OLD = key(1);
    private static final WalKey NEW = key(2);

    @Test
    void singleKeyProviderOnlyAnswersForItsCurrentKey() {
        WalKeyProvider keys = () -> NEW;
        assertEquals(Optional.of(NEW), keys.byId(NEW.keyId()));
        assertEquals(Optional.empty(), keys.byId(OLD.keyId()));
    }

    @Test
    void ringReadsFramesSealedByTheRetiredKey() throws Exception {
        byte[] frame =
                WalFormat.encodeFrame(
                        new WalFormat.Frame(
                                "terminal",
                                UUID.randomUUID(),
                                UUID.randomUUID(),
                                1,
                                "payload".getBytes()),
                        OLD,
                        new byte[WalFormat.NONCE_BYTES]);
        WalKeyProvider rotated = ring(NEW, OLD);

        WalFormat.Frame decoded =
                WalFormat.decodeFrame(ByteBuffer.wrap(frame), id -> rotated.byId(id).orElse(null));
        assertEquals("terminal", decoded.eventKind());
        assertTrue(java.util.Arrays.equals("payload".getBytes(), decoded.payload()));

        WalKeyProvider currentOnly = ring(NEW);
        assertThrows(
                WalFormat.CorruptFrameException.class,
                () ->
                        WalFormat.decodeFrame(
                                ByteBuffer.wrap(frame), id -> currentOnly.byId(id).orElse(null)));
    }

    private static WalKeyProvider ring(WalKey current, WalKey... retired) {
        Map<String, WalKey> keys = new LinkedHashMap<>();
        keys.put(current.keyId(), current);
        for (WalKey key : retired) {
            keys.put(key.keyId(), key);
        }
        return new WalKeyProvider() {
            @Override
            public WalKey current() {
                return current;
            }

            @Override
            public Optional<WalKey> byId(String keyId) {
                return Optional.ofNullable(keys.get(keyId));
            }
        };
    }

    private static WalKey key(int fill) {
        byte[] raw = new byte[32];
        java.util.Arrays.fill(raw, (byte) fill);
        return new WalKey("key-" + fill, raw);
    }
}
