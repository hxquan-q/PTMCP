package local.ptmcp.gateway.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import local.ptmcp.common.crypto.SealedEnvelope;
import local.ptmcp.persistence.audit.wal.WalKey;
import local.ptmcp.persistence.audit.wal.WalKeyProvider;

import org.junit.jupiter.api.Test;

import java.util.Base64;
import java.util.Optional;

/** The WAL key ring has to agree with {@link SealedEnvelope} on key ids and rotation order. */
class WalKeyRingTest {
    private static final String OLD = base64(3);
    private static final String NEW = base64(4);

    @Test
    void writesWithFirstKeyAndReadsRetiredOnes() {
        WalKeyProvider ring = new McpGatewayConfiguration.WalKeyRing(NEW + ", " + OLD);
        WalKey current = ring.current();
        assertEquals(SealedEnvelope.keyIdOf(raw(NEW)), current.keyId());
        assertEquals(Optional.of(current), ring.byId(current.keyId()));
        assertEquals(Optional.empty(), ring.byId("local:0000000000000000"));
        assertTrue(ring.byId(SealedEnvelope.keyIdOf(raw(OLD))).isPresent());
    }

    @Test
    void rejectsInvalidConfiguration() {
        assertThrows(IllegalStateException.class, () -> new McpGatewayConfiguration.WalKeyRing(""));
        assertThrows(
                IllegalStateException.class,
                () -> new McpGatewayConfiguration.WalKeyRing("not-base64!!"));
        assertThrows(
                IllegalStateException.class,
                () ->
                        new McpGatewayConfiguration.WalKeyRing(
                                Base64.getEncoder().encodeToString(new byte[16])));
    }

    private static String base64(int fill) {
        byte[] value = new byte[32];
        java.util.Arrays.fill(value, (byte) fill);
        return Base64.getEncoder().encodeToString(value);
    }

    private static byte[] raw(String base64Key) {
        return Base64.getDecoder().decode(base64Key);
    }
}
