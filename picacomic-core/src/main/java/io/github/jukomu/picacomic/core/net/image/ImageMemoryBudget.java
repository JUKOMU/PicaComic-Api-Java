package io.github.jukomu.picacomic.core.net.image;

import io.github.jukomu.picacomic.api.exception.ImageFetchException;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * 一个 client 共享的在途图片 payload 预算。
 */
public final class ImageMemoryBudget {

    public static final long MAX_PAYLOAD_BYTES = 64L * 1024L * 1024L;

    private final Object monitor = new Object();
    private long reservedBytes;

    public ImageMemoryBudget() {
    }

    /**
     * 在 deadline 前保留 payload 空间。等待期间会周期性检查取消与 client close。
     */
    public Reservation acquire(long bytes,
                               long deadlineNanos,
                               BooleanSupplier cancelled,
                               Supplier<ImageFetchException.Reason> cancellationReason) {
        if (bytes < 0 || bytes > MAX_PAYLOAD_BYTES) {
            throw new IllegalArgumentException("Image payload reservation is outside the fixed budget");
        }
        if (cancelled == null || cancellationReason == null) {
            throw new NullPointerException("Cancellation callbacks cannot be null");
        }
        synchronized (monitor) {
            for (;;) {
                if (cancelled.getAsBoolean()) {
                    throw cancelledException(cancellationReason.get());
                }
                if (reservedBytes <= MAX_PAYLOAD_BYTES - bytes) {
                    reservedBytes += bytes;
                    return new Reservation(this, bytes);
                }
                long remaining = deadlineNanos - System.nanoTime();
                if (remaining <= 0) {
                    throw new ImageFetchException(ImageFetchException.Reason.TIMEOUT);
                }
                try {
                    long waitMillis = Math.max(1L, Math.min(50L, remaining / 1_000_000L));
                    monitor.wait(waitMillis);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new ImageFetchException(ImageFetchException.Reason.CANCELLED, exception);
                }
            }
        }
    }

    public long getReservedBytes() {
        synchronized (monitor) {
            return reservedBytes;
        }
    }

    /**
     * 唤醒所有等待者，使 close/cancel 能尽快重新检查状态。
     */
    public void signalWaiters() {
        synchronized (monitor) {
            monitor.notifyAll();
        }
    }

    private static ImageFetchException cancelledException(ImageFetchException.Reason reason) {
        ImageFetchException.Reason actual = reason == null
                ? ImageFetchException.Reason.CANCELLED
                : reason;
        return new ImageFetchException(actual);
    }

    public static final class Reservation implements AutoCloseable {
        private final ImageMemoryBudget owner;
        private final long bytes;
        private final AtomicBoolean released = new AtomicBoolean();

        private Reservation(ImageMemoryBudget owner, long bytes) {
            this.owner = owner;
            this.bytes = bytes;
        }

        public long getBytes() {
            return bytes;
        }

        @Override
        public void close() {
            if (!released.compareAndSet(false, true)) {
                return;
            }
            synchronized (owner.monitor) {
                owner.reservedBytes -= bytes;
                owner.monitor.notifyAll();
            }
        }
    }
}
