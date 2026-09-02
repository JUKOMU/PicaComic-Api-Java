package io.github.jukomu.picacomic.api.client;

/**
 * A synchronous, single-use handle for one logical API operation.
 *
 * <p>The handle does not create a library-owned worker thread. {@link #execute()}
 * performs the operation on the calling thread, while {@link #cancel()} may be
 * called from another thread to cancel the current and any future sub-requests
 * belonging to this operation.</p>
 *
 * @param <T> operation result type
 */
public interface PicaRequest<T> extends AutoCloseable {

    /**
     * Executes this operation once on the calling thread.
     *
     * @return operation result
     */
    T execute();

    /**
     * Cancels this operation. The method is thread-safe and idempotent.
     */
    void cancel();

    /**
     * Indicates whether this handle was cancelled explicitly or by its client.
     *
     * @return whether cancellation was requested
     */
    boolean isCancelled();

    /**
     * Cancels unfinished work and releases handle-owned state.
     */
    @Override
    void close();
}
