package local.ptmcp.guard.budget;

import local.ptmcp.common.error.DomainException;
import local.ptmcp.common.error.ErrorCode;

import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.select.Select;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Parses untrusted SQL on an isolated, bounded worker pool under a hard deadline.
 *
 * <p>{@link Future#cancel(boolean)} cannot prove that a parser thread stopped. When a parse times
 * out, the whole worker generation is quarantined (interrupted and never reused) and a fresh
 * generation serves later requests. The number of quarantined generations is capped; once the cap
 * is reached the parser fails closed for all further input.
 */
public final class BoundedParser implements AutoCloseable {
    public static final int MAX_SQL_BYTES = 64 * 1024;
    public static final Duration DEFAULT_TIMEOUT = Duration.ofMillis(500);

    private static final int DEFAULT_POOL_SIZE = 2;
    private static final int DEFAULT_QUEUE_CAPACITY = 8;
    private static final int DEFAULT_MAX_QUARANTINES = 4;

    private final ParserEngine engine;
    private final int poolSize;
    private final int queueCapacity;
    private final Duration timeout;
    private final int maxQuarantines;
    private final Object lifecycle = new Object();
    private final List<ExecutorService> generations = new ArrayList<>();
    private final AtomicInteger generationCounter = new AtomicInteger();

    private ExecutorService executor;
    private int quarantined;
    private boolean closed;

    public BoundedParser(Duration timeout) {
        this(
                BoundedParser::parseDefault,
                DEFAULT_POOL_SIZE,
                DEFAULT_QUEUE_CAPACITY,
                timeout,
                DEFAULT_MAX_QUARANTINES);
    }

    BoundedParser(
            ParserEngine engine,
            int poolSize,
            int queueCapacity,
            Duration timeout,
            int maxQuarantines) {
        this.engine = Objects.requireNonNull(engine, "engine");
        if (poolSize < 1 || queueCapacity < 1 || maxQuarantines < 1) {
            throw new IllegalArgumentException("pool size, queue capacity and quarantine cap");
        }
        this.poolSize = poolSize;
        this.queueCapacity = queueCapacity;
        this.timeout = requirePositive(timeout);
        this.maxQuarantines = maxQuarantines;
        this.executor = newWorkerGeneration();
    }

    public Select parse(String sql) {
        if (sql == null) {
            throw new DomainException(ErrorCode.INVALID_ARGUMENT);
        }
        if (sql.getBytes(StandardCharsets.UTF_8).length > MAX_SQL_BYTES) {
            throw new DomainException(ErrorCode.SQL_BUDGET_EXCEEDED);
        }
        Future<Select> future = submit(sql);
        try {
            return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException expired) {
            future.cancel(true);
            quarantineGeneration();
            throw new DomainException(ErrorCode.SQL_BUDGET_EXCEEDED);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            future.cancel(true);
            throw new DomainException(ErrorCode.INTERNAL_ERROR);
        } catch (ExecutionException failure) {
            // Never forward parser or engine exception text: it may echo the SQL or driver detail.
            throw new DomainException(ErrorCode.SQL_SYNTAX_INVALID);
        }
    }

    @Override
    public void close() {
        List<ExecutorService> toStop;
        synchronized (lifecycle) {
            if (closed) {
                return;
            }
            closed = true;
            executor = null;
            toStop = new ArrayList<>(generations);
            generations.clear();
        }
        toStop.forEach(ExecutorService::shutdownNow);
    }

    private Future<Select> submit(String sql) {
        synchronized (lifecycle) {
            if (closed) {
                throw new IllegalStateException("parser is closed");
            }
            if (executor == null) {
                throw new DomainException(ErrorCode.SQL_BUDGET_EXCEEDED);
            }
            try {
                return executor.submit(() -> engine.parse(sql));
            } catch (RejectedExecutionException full) {
                throw new DomainException(ErrorCode.SQL_BUDGET_EXCEEDED);
            }
        }
    }

    private void quarantineGeneration() {
        synchronized (lifecycle) {
            if (closed) {
                return;
            }
            ExecutorService poisoned = executor;
            executor = null;
            if (poisoned != null) {
                poisoned.shutdownNow();
            }
            quarantined++;
            if (quarantined < maxQuarantines) {
                executor = newWorkerGeneration();
            }
        }
    }

    private ExecutorService newWorkerGeneration() {
        int generation = generationCounter.incrementAndGet();
        ThreadPoolExecutor pool =
                new ThreadPoolExecutor(
                        poolSize,
                        poolSize,
                        0L,
                        TimeUnit.MILLISECONDS,
                        new ArrayBlockingQueue<>(queueCapacity),
                        new ParserThreadFactory(generation),
                        new ThreadPoolExecutor.AbortPolicy());
        pool.prestartAllCoreThreads();
        generations.add(pool);
        return pool;
    }

    private static Select parseDefault(String sql) throws Exception {
        Statement statement = CCJSqlParserUtil.parse(sql);
        if (statement instanceof Select select) {
            return select;
        }
        throw new IllegalArgumentException("statement is not a single SELECT");
    }

    private static Duration requirePositive(Duration duration) {
        Objects.requireNonNull(duration, "timeout");
        if (duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException("timeout must be positive");
        }
        return duration;
    }

    /** Testable parse boundary; production construction uses the JSQLParser engine. */
    @FunctionalInterface
    interface ParserEngine {
        Select parse(String sql) throws Exception;
    }

    private static final class ParserThreadFactory implements ThreadFactory {
        private final int generation;
        private final AtomicInteger sequence = new AtomicInteger();

        ParserThreadFactory(int generation) {
            this.generation = generation;
        }

        @Override
        public Thread newThread(Runnable task) {
            Thread thread =
                    new Thread(
                            task,
                            "ptmcp-sql-parser-" + generation + "-" + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        }
    }
}
