package local.ptmcp.datasource;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

/** Cooperative cancellation handle owned by the caller, never by the connection pool. */
public final class ReadOnlyCancellation {
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final ReentrantLock actionLock = new ReentrantLock();
    private volatile boolean tainted;
    private Runnable action;

    public void cancel() {
        cancelled.set(true);
        actionLock.lock();
        try {
            Runnable current = action;
            action = null;
            if (current != null) {
                current.run();
            }
        } finally {
            actionLock.unlock();
        }
    }

    public boolean isCancelled() {
        return cancelled.get();
    }

    boolean isTainted() {
        return tainted;
    }

    void install(Runnable cancelAction) {
        actionLock.lock();
        try {
            if (cancelled.get()) {
                cancelAction.run();
            } else {
                action = cancelAction;
            }
        } finally {
            actionLock.unlock();
        }
    }

    boolean clear(long timeoutMillis) {
        boolean locked;
        try {
            locked =
                    cancelled.get()
                            ? actionLock.tryLock(timeoutMillis, TimeUnit.MILLISECONDS)
                            : actionLock.tryLock();
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            locked = false;
        }
        if (!locked) {
            tainted = true;
            return false;
        }
        try {
            action = null;
            return true;
        } finally {
            actionLock.unlock();
        }
    }
}
