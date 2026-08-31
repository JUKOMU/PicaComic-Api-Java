package io.github.jukomu.picacomic.core;

import io.github.jukomu.picacomic.api.exception.ImageFetchException;
import io.github.jukomu.picacomic.core.net.image.ImageMemoryBudget;
import org.junit.jupiter.api.Test;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ImageMemoryBudgetTest {

    @Test
    void reservationsAreBoundedWaitableAndReleasedExactlyOnce() throws Exception {
        ImageMemoryBudget budget = new ImageMemoryBudget();
        ImageMemoryBudget.Reservation full = budget.acquire(
                ImageMemoryBudget.MAX_PAYLOAD_BYTES,
                System.nanoTime() + TimeUnit.SECONDS.toNanos(3),
                () -> false,
                () -> ImageFetchException.Reason.CANCELLED);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<ImageMemoryBudget.Reservation> waiting = executor.submit(() -> budget.acquire(
                    1,
                    System.nanoTime() + TimeUnit.SECONDS.toNanos(3),
                    () -> false,
                    () -> ImageFetchException.Reason.CANCELLED));
            Thread.sleep(100);
            assertFalse(waiting.isDone());
            full.close();
            full.close();
            ImageMemoryBudget.Reservation one = waiting.get(3, TimeUnit.SECONDS);
            assertEquals(1, one.getBytes());
            one.close();
            assertEquals(0, budget.getReservedBytes());
        } finally {
            full.close();
            executor.shutdownNow();
        }
    }

    @Test
    void aWaitingReservationCanBeCancelled() throws Exception {
        ImageMemoryBudget budget = new ImageMemoryBudget();
        ImageMemoryBudget.Reservation full = budget.acquire(
                ImageMemoryBudget.MAX_PAYLOAD_BYTES,
                System.nanoTime() + TimeUnit.SECONDS.toNanos(3),
                () -> false,
                () -> ImageFetchException.Reason.CANCELLED);
        java.util.concurrent.atomic.AtomicBoolean cancelled = new java.util.concurrent.atomic.AtomicBoolean();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<ImageMemoryBudget.Reservation> waiting = executor.submit(() -> budget.acquire(
                    1,
                    System.nanoTime() + TimeUnit.SECONDS.toNanos(3),
                    cancelled::get,
                    () -> ImageFetchException.Reason.CANCELLED));
            Thread.sleep(100);
            cancelled.set(true);
            budget.signalWaiters();
            ExecutionException exception = org.junit.jupiter.api.Assertions.assertThrows(ExecutionException.class,
                    () -> waiting.get(3, TimeUnit.SECONDS));
            assertTrue(exception.getCause() instanceof ImageFetchException);
            assertEquals(ImageFetchException.Reason.CANCELLED,
                    ((ImageFetchException) exception.getCause()).getReason());
        } finally {
            full.close();
            executor.shutdownNow();
        }
    }
}
