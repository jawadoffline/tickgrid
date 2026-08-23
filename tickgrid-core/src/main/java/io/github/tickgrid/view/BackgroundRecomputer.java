package io.github.tickgrid.view;

import java.util.concurrent.locks.LockSupport;

/**
 * Drives {@link ViewModel#maybeRecompute} from one daemon thread, so sorting never runs on the
 * render thread.
 *
 * <p>Deliberately not a {@code ScheduledExecutorService}: the {@link SortPolicy} already decides
 * when a recompute is due, so this only needs to ask often enough not to add latency of its own.
 * It parks between checks rather than spinning, because a view that is not due costs nothing and
 * should not cost a core.
 */
public final class BackgroundRecomputer implements AutoCloseable {

    private final ViewModel viewModel;
    private final long tickNanos;
    private final Thread thread;
    private volatile boolean running = true;

    public BackgroundRecomputer(ViewModel viewModel) {
        this(viewModel, 8);                     // twice the default 4 Hz policy: no added latency
    }

    public BackgroundRecomputer(ViewModel viewModel, long tickMillis) {
        if (tickMillis <= 0) throw new IllegalArgumentException("tick must be positive");
        this.viewModel = viewModel;
        this.tickNanos = tickMillis * 1_000_000L;
        this.thread = new Thread(this::loop, "tickgrid-view-recompute");
        this.thread.setDaemon(true);
    }

    public BackgroundRecomputer start() {
        thread.start();
        return this;
    }

    private void loop() {
        while (running) {
            try {
                viewModel.maybeRecompute(System.nanoTime());
            } catch (Throwable t) {
                // A recompute must never take the thread down with it: the last good snapshot stays
                // published and the grid keeps rendering, which is far better than a frozen view.
                Thread.UncaughtExceptionHandler h = Thread.currentThread().getUncaughtExceptionHandler();
                if (h != null) h.uncaughtException(Thread.currentThread(), t);
            }
            LockSupport.parkNanos(tickNanos);
        }
    }

    @Override
    public void close() {
        running = false;
        LockSupport.unpark(thread);
    }

    /** Blocks until the thread has stopped. For orderly shutdown in tests. */
    public void closeAndJoin() throws InterruptedException {
        close();
        thread.join(2_000);
    }
}
