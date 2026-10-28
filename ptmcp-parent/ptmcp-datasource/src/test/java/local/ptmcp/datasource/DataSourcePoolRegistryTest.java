package local.ptmcp.datasource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import local.ptmcp.common.error.DomainException;
import local.ptmcp.common.error.ErrorCode;
import local.ptmcp.common.resource.DatabaseEngine;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicInteger;

import javax.sql.DataSource;

class DataSourcePoolRegistryTest {
    @Test
    void delayedActivationCannotResurrectAnOlderConfiguration() {
        AtomicInteger created = new AtomicInteger();
        try (DataSourcePoolRegistry registry =
                new DataSourcePoolRegistry(
                        (key, secret, limits) -> {
                            created.incrementAndGet();
                            return pool(new AtomicInteger());
                        })) {
            PoolHandle current =
                    registry.activate(key(2, "new.invalid"), new char[] {'x'}, PoolLimits.of(1));
            DomainException failure =
                    assertThrows(
                            DomainException.class,
                            () ->
                                    registry.activate(
                                            key(1, "old.invalid"),
                                            new char[] {'x'},
                                            PoolLimits.of(1)));
            assertEquals(ErrorCode.SCHEMA_STALE, failure.errorCode());
            assertEquals(1, created.get());
            assertSame(current, registry.current(1).orElseThrow());
            assertEquals(PoolHandle.State.ACTIVE, current.state());
        }
    }

    @Test
    void oneVersionCannotIdentifyTwoPhysicalTargets() {
        try (DataSourcePoolRegistry registry =
                new DataSourcePoolRegistry((key, secret, limits) -> pool(new AtomicInteger()))) {
            PoolHandle current =
                    registry.activate(key(1, "first.invalid"), new char[] {'x'}, PoolLimits.of(1));
            DomainException failure =
                    assertThrows(
                            DomainException.class,
                            () ->
                                    registry.activate(
                                            key(1, "second.invalid"),
                                            new char[] {'x'},
                                            PoolLimits.of(1)));
            assertEquals(ErrorCode.SCHEMA_STALE, failure.errorCode());
            assertSame(current, registry.current(1).orElseThrow());
        }
    }

    private static PoolKey key(long version, String host) {
        return new PoolKey(
                1, version, DatabaseEngine.MYSQL, host, 3306, "synthetic", "reader", version);
    }

    @Test
    void failedDisposalRemainsOwnedAndCanBeRetried() {
        AtomicInteger attempts = new AtomicInteger();
        DataSource failingOnce =
                (DataSource)
                        Proxy.newProxyInstance(
                                getClass().getClassLoader(),
                                new Class<?>[] {DataSource.class, AutoCloseable.class},
                                (proxy, method, args) -> {
                                    if ("close".equals(method.getName())) {
                                        if (attempts.incrementAndGet() == 1) {
                                            throw new SQLException("Synthetic disposal failure");
                                        }
                                        return null;
                                    }
                                    throw new UnsupportedOperationException(method.getName());
                                });
        try (DataSourcePoolRegistry registry =
                new DataSourcePoolRegistry(
                        (key, secret, limits) ->
                                key.configVersion() == 1
                                        ? failingOnce
                                        : pool(new AtomicInteger()))) {
            PoolHandle original =
                    registry.activate(key(1, "first.invalid"), new char[] {'x'}, PoolLimits.of(1));
            PoolHandle.Lease lease = original.lease();
            registry.activate(key(2, "second.invalid"), new char[] {'x'}, PoolLimits.of(1));
            lease.close();
            assertEquals(PoolHandle.State.DRAINING, original.state());
            assertEquals(1, registry.failedDisposals());
            registry.retryDisposals();
            assertEquals(PoolHandle.State.CLOSED, original.state());
            assertEquals(0, registry.failedDisposals());
            assertEquals(2, attempts.get());
        }
    }

    @Test
    void rotationAndShutdownWaitForTheExactGenerationLease() {
        AtomicInteger closed = new AtomicInteger();
        try (DataSourcePoolRegistry registry =
                new DataSourcePoolRegistry((key, secret, limits) -> pool(closed))) {
            PoolHandle first =
                    registry.activate(key(1, "first.invalid"), new char[] {'x'}, PoolLimits.of(1));
            PoolHandle.Lease lease = first.lease();
            PoolHandle next =
                    registry.activate(key(2, "second.invalid"), new char[] {'x'}, PoolLimits.of(1));
            assertEquals(PoolHandle.State.DRAINING, first.state());
            assertEquals(0, closed.get());
            assertThrows(DomainException.class, first::lease);
            lease.close();
            lease.close();
            assertEquals(PoolHandle.State.CLOSED, first.state());
            assertEquals(1, closed.get());
            PoolHandle.Lease nextLease = next.lease();
            registry.close();
            assertEquals(PoolHandle.State.DRAINING, next.state());
            assertEquals(1, closed.get());
            nextLease.close();
            assertEquals(PoolHandle.State.CLOSED, next.state());
            assertEquals(2, closed.get());
        }
    }

    private static DataSource pool(AtomicInteger closed) {
        return (DataSource)
                Proxy.newProxyInstance(
                        DataSourcePoolRegistryTest.class.getClassLoader(),
                        new Class<?>[] {DataSource.class, AutoCloseable.class},
                        (proxy, method, args) -> {
                            if ("close".equals(method.getName())) {
                                closed.incrementAndGet();
                                return null;
                            }
                            throw new UnsupportedOperationException(method.getName());
                        });
    }
}
