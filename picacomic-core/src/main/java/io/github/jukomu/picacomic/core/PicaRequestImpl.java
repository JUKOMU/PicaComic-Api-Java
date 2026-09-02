package io.github.jukomu.picacomic.core;

import io.github.jukomu.picacomic.api.client.PicaRequest;
import io.github.jukomu.picacomic.api.exception.PicaApiException;
import io.github.jukomu.picacomic.api.exception.NetworkException;

import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Client-owned lifecycle for one logical synchronous operation.
 */
final class PicaRequestImpl<T> implements PicaRequest<T> {

    @FunctionalInterface
    interface Operation<T> {
        T run(PicaRequestImpl<T> request);
    }

    private final Operation<T> operation;
    private final BooleanSupplier clientClosed;
    private final Consumer<PicaRequestImpl<?>> onStarted;
    private final Consumer<PicaRequestImpl<?>> onFinished;
    private final Runnable onReleased;
    private final Consumer<PicaApiException> onTerminalFailure;
    private final AtomicReference<State> state = new AtomicReference<>(State.NEW);
    private final AtomicReference<PicaApiException.Reason> termination = new AtomicReference<>();
    private final AtomicReference<okhttp3.Call> currentCall = new AtomicReference<>();
    private final AtomicBoolean released = new AtomicBoolean();
    private final AtomicBoolean terminalFailureNotified = new AtomicBoolean();
    private final Object lifecycleLock = new Object();
    private boolean executionAttempted;

    PicaRequestImpl(Operation<T> operation,
                    BooleanSupplier clientClosed,
                    Consumer<PicaRequestImpl<?>> onStarted,
                    Consumer<PicaRequestImpl<?>> onFinished) {
        this(operation, clientClosed, onStarted, onFinished, () -> {
        });
    }

    PicaRequestImpl(Operation<T> operation,
                    BooleanSupplier clientClosed,
                    Consumer<PicaRequestImpl<?>> onStarted,
                    Consumer<PicaRequestImpl<?>> onFinished,
                    Runnable onReleased) {
        this(operation, clientClosed, onStarted, onFinished, onReleased, ignored -> {
        });
    }

    PicaRequestImpl(Operation<T> operation,
                    BooleanSupplier clientClosed,
                    Consumer<PicaRequestImpl<?>> onStarted,
                    Consumer<PicaRequestImpl<?>> onFinished,
                    Runnable onReleased,
                    Consumer<PicaApiException> onTerminalFailure) {
        this.operation = Objects.requireNonNull(operation, "Request operation cannot be null");
        this.clientClosed = Objects.requireNonNull(clientClosed, "Client closed callback cannot be null");
        this.onStarted = Objects.requireNonNull(onStarted, "Request start callback cannot be null");
        this.onFinished = Objects.requireNonNull(onFinished, "Request finish callback cannot be null");
        this.onReleased = Objects.requireNonNull(onReleased, "Request release callback cannot be null");
        this.onTerminalFailure = Objects.requireNonNull(onTerminalFailure,
                "Terminal failure callback cannot be null");
    }

    @Override
    public T execute() {
        synchronized (lifecycleLock) {
            if (executionAttempted) {
                throw new IllegalStateException("A Pica request can only be executed once");
            }
            executionAttempted = true;
            State observed = state.get();
            if (observed != State.NEW) {
                throw terminalCancellation();
            }
            state.set(State.RUNNING);
        }

        onStarted.accept(this);
        try {
            checkBeforeWork();
            T result = operation.run(this);
            checkBeforeWork();
            completeSuccess();
            return result;
        } catch (PicaApiException exception) {
            throw fail(exception);
        } catch (IllegalArgumentException | NullPointerException | IllegalStateException exception) {
            failNonApi(exception);
            throw exception;
        } catch (RuntimeException exception) {
            throw fail(new PicaApiException(PicaApiException.Reason.INTERNAL, exception));
        } finally {
            onFinished.accept(this);
            release();
        }
    }

    /**
     * Checks cancellation at each logical request boundary.
     */
    void checkBeforeWork() {
        if (clientClosed.getAsBoolean()) {
            signalClientClosed();
        }
        PicaApiException.Reason reason = termination.get();
        if (reason != null) {
            throw new PicaApiException(reason);
        }
    }

    /**
     * Returns the operation's current cancellation reason for transport I/O.
     */
    PicaApiException failureForIOException(IOException exception) {
        if (clientClosed.getAsBoolean()) {
            markClientClosed();
        }
        PicaApiException.Reason reason = termination.get();
        if (reason == null) {
            reason = exception instanceof java.io.InterruptedIOException
                    ? PicaApiException.Reason.TIMEOUT
                    : PicaApiException.Reason.NETWORK;
        }
        return reason == PicaApiException.Reason.CANCELLED
                || reason == PicaApiException.Reason.CLIENT_CLOSED
                ? new PicaApiException(reason, exception)
                : new NetworkException(reason, exception);
    }

    PicaApiException effective(PicaApiException exception) {
        if (clientClosed.getAsBoolean()) {
            markClientClosed();
        }
        PicaApiException.Reason reason = termination.get();
        return reason == null ? exception : exception.withReason(reason);
    }

    void attachCall(okhttp3.Call call) {
        currentCall.set(call);
        if (clientClosed.getAsBoolean() || termination.get() != null) {
            call.cancel();
        }
    }

    void detachCall(okhttp3.Call call) {
        currentCall.compareAndSet(call, null);
    }

    void signalClientClosed() {
        markClientClosed();
        throw new PicaApiException(PicaApiException.Reason.CLIENT_CLOSED);
    }

    private void markClientClosed() {
        synchronized (lifecycleLock) {
            if (state.get() == State.SUCCEEDED || state.get() == State.FAILED) {
                return;
            }
            termination.set(PicaApiException.Reason.CLIENT_CLOSED);
            if (state.get() == State.NEW) {
                state.set(State.CANCELLED);
                release();
            }
            okhttp3.Call call = currentCall.get();
            if (call != null) {
                call.cancel();
            }
        }
    }

    void cancelFromLogout() {
        cancelWithReason(PicaApiException.Reason.CANCELLED);
    }

    void closeFromClient() {
        cancelWithReason(PicaApiException.Reason.CLIENT_CLOSED);
    }

    @Override
    public void cancel() {
        cancelWithReason(PicaApiException.Reason.CANCELLED);
    }

    private void cancelWithReason(PicaApiException.Reason reason) {
        boolean notify = false;
        synchronized (lifecycleLock) {
            State current = state.get();
            if (current == State.SUCCEEDED || current == State.FAILED || current == State.CLOSED) {
                return;
            }
            if (reason != PicaApiException.Reason.CLIENT_CLOSED && clientClosed.getAsBoolean()) {
                reason = PicaApiException.Reason.CLIENT_CLOSED;
            }
            termination.set(reason);
            okhttp3.Call call = currentCall.get();
            if (call != null) {
                call.cancel();
            }
            if (current == State.NEW) {
                state.set(State.CANCELLED);
                notify = true;
            }
        }
        if (notify) {
            onFinished.accept(this);
            release();
        }
    }

    @Override
    public boolean isCancelled() {
        if (clientClosed.getAsBoolean()) {
            try {
                signalClientClosed();
            } catch (PicaApiException ignored) {
                // The state is now observable through the termination field.
            }
        }
        PicaApiException.Reason reason = termination.get();
        return reason == PicaApiException.Reason.CANCELLED
                || reason == PicaApiException.Reason.CLIENT_CLOSED;
    }

    @Override
    public void close() {
        cancelWithReason(PicaApiException.Reason.CANCELLED);
        synchronized (lifecycleLock) {
            if (state.get() == State.CANCELLED) {
                state.set(State.CLOSED);
                release();
            }
        }
    }

    private void release() {
        if (released.compareAndSet(false, true)) {
            onReleased.run();
        }
    }

    private PicaApiException fail(PicaApiException exception) {
        PicaApiException effective = effective(exception);
        boolean terminalFailure = effective.getReason() == PicaApiException.Reason.CANCELLED
                || effective.getReason() == PicaApiException.Reason.CLIENT_CLOSED;
        synchronized (lifecycleLock) {
            if (terminalFailure) {
                state.set(State.CANCELLED);
            } else {
                state.set(State.FAILED);
            }
        }
        if (terminalFailure && terminalFailureNotified.compareAndSet(false, true)) {
            try {
                onTerminalFailure.accept(effective);
            } catch (RuntimeException ignored) {
                // A lifecycle cleanup callback must not replace the request failure.
            }
        }
        return effective;
    }

    private void completeSuccess() {
        PicaApiException.Reason reason;
        synchronized (lifecycleLock) {
            if (clientClosed.getAsBoolean()) {
                termination.set(PicaApiException.Reason.CLIENT_CLOSED);
            }
            reason = termination.get();
            if (reason == null && state.get() == State.RUNNING) {
                state.set(State.SUCCEEDED);
                return;
            }
            if (reason == null) {
                reason = PicaApiException.Reason.CANCELLED;
            }
            state.set(State.CANCELLED);
        }
        throw new PicaApiException(reason);
    }

    private void failNonApi(RuntimeException exception) {
        synchronized (lifecycleLock) {
            state.set(State.FAILED);
        }
    }

    private PicaApiException terminalCancellation() {
        if (clientClosed.getAsBoolean()) {
            markClientClosed();
            return new PicaApiException(PicaApiException.Reason.CLIENT_CLOSED);
        }
        PicaApiException.Reason reason = termination.get();
        if (reason == null) {
            return new PicaApiException(PicaApiException.Reason.CANCELLED);
        }
        return new PicaApiException(reason);
    }

    private enum State {
        NEW,
        RUNNING,
        SUCCEEDED,
        FAILED,
        CANCELLED,
        CLOSED
    }
}
