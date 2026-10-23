package local.ptmcp.datasource;

/** Hard pool bounds from the connection budget contract. */
public record PoolLimits(int maxPoolSize, long checkoutTimeoutMillis) {
    public static final int MIN_POOL_SIZE = 1;
    public static final int MAX_POOL_SIZE = 8;
    public static final long DEFAULT_CHECKOUT_TIMEOUT_MILLIS = 3_000;
    private static final long MIN_CHECKOUT_TIMEOUT_MILLIS = 250;
    private static final long MAX_CHECKOUT_TIMEOUT_MILLIS = 30_000;

    public PoolLimits {
        if (maxPoolSize < MIN_POOL_SIZE || maxPoolSize > MAX_POOL_SIZE) {
            throw new IllegalArgumentException("maxPoolSize must be between 1 and 8");
        }
        if (checkoutTimeoutMillis < MIN_CHECKOUT_TIMEOUT_MILLIS
                || checkoutTimeoutMillis > MAX_CHECKOUT_TIMEOUT_MILLIS) {
            throw new IllegalArgumentException("checkoutTimeoutMillis is out of range");
        }
    }

    public static PoolLimits of(int maxPoolSize) {
        return new PoolLimits(maxPoolSize, DEFAULT_CHECKOUT_TIMEOUT_MILLIS);
    }
}
