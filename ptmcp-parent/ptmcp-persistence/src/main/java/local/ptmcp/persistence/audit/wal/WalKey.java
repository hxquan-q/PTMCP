package local.ptmcp.persistence.audit.wal;

import local.ptmcp.common.error.DomainException;
import local.ptmcp.common.error.ErrorCode;

import java.util.Arrays;
import java.util.Objects;

/**
 * One AES-256-GCM data key for WAL frame encryption. Keys come from a provider, never from the SQL
 * path; the WAL directory stores only the key id, so rotation keeps every key readable until all
 * segments referencing it are consumed.
 */
public record WalKey(String keyId, byte[] aesKey) {
    public WalKey {
        Objects.requireNonNull(keyId, "keyId");
        if (keyId.isBlank() || keyId.length() > 128) {
            throw new DomainException(ErrorCode.INVALID_ARGUMENT);
        }
        Objects.requireNonNull(aesKey, "aesKey");
        if (aesKey.length != 32) {
            throw new DomainException(ErrorCode.INVALID_ARGUMENT);
        }
        aesKey = aesKey.clone();
    }

    @Override
    public byte[] aesKey() {
        return aesKey.clone();
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof WalKey key
                && keyId.equals(key.keyId)
                && Arrays.equals(aesKey, key.aesKey);
    }

    @Override
    public int hashCode() {
        return 31 * keyId.hashCode() + Arrays.hashCode(aesKey);
    }

    @Override
    public String toString() {
        return "WalKey[keyId=" + keyId + "]";
    }
}
