package local.ptmcp.persistence.org;

import local.ptmcp.common.error.DomainException;
import local.ptmcp.common.error.ErrorCode;

/**
 * Encodes the cross-type authorization resource id space into the single positive long that {@link
 * local.ptmcp.common.authz.GrantRow} carries: the resource type occupies the upper bits so
 * datasource, namespace and object table ids never collide.
 */
public final class ResourceIds {
    public enum Type {
        DATASOURCE(1),
        NAMESPACE(2),
        OBJECT(3);

        final long code;

        Type(long code) {
            this.code = code;
        }
    }

    private static final long TYPE_SHIFT = 48;

    private ResourceIds() {}

    public static long encode(Type type, long tableId) {
        if (tableId <= 0 || tableId >= (1L << TYPE_SHIFT)) {
            throw new DomainException(ErrorCode.INVALID_ARGUMENT);
        }
        return (type.code << TYPE_SHIFT) | tableId;
    }

    public static Type typeOf(long resourceId) {
        long code = resourceId >>> TYPE_SHIFT;
        for (Type type : Type.values()) {
            if (type.code == code) {
                return type;
            }
        }
        throw new DomainException(ErrorCode.INVALID_ARGUMENT);
    }

    public static long tableIdOf(long resourceId) {
        return resourceId & ((1L << TYPE_SHIFT) - 1);
    }
}
