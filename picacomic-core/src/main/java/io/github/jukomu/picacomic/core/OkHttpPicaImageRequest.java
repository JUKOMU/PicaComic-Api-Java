package io.github.jukomu.picacomic.core;

import io.github.jukomu.picacomic.api.client.PicaImageRequest;
import io.github.jukomu.picacomic.api.exception.ImageFetchException;
import io.github.jukomu.picacomic.api.model.PicaImage;
import okhttp3.Call;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.BufferedSource;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * 图片专用 OkHttp 请求句柄。它不继承 API client 的 request、Cookie 或拦截器。
 */
final class OkHttpPicaImageRequest implements PicaImageRequest {

    private static final int SCRATCH_BYTES = 8 * 1024;

    private final PicaImage image;
    private final OkHttpClient imageClient;
    private final ImageLocatorResolver resolver;
    private final Semaphore readerSlots;
    private final BooleanSupplier clientClosed;
    private final Consumer<Call> registerCall;
    private final Consumer<Call> unregisterCall;
    private final Runnable onClosed;

    private final AtomicReference<State> state = new AtomicReference<>(State.NEW);
    private final AtomicReference<ImageFetchException.Reason> termination = new AtomicReference<>();
    private final AtomicReference<Call> currentCall = new AtomicReference<>();
    private final AtomicBoolean closeRequested = new AtomicBoolean();
    private final AtomicBoolean deregistered = new AtomicBoolean();
    private final Object lifecycleLock = new Object();

    OkHttpPicaImageRequest(PicaImage image,
                           OkHttpClient imageClient,
                           ImageLocatorResolver resolver,
                           Semaphore readerSlots,
                           BooleanSupplier clientClosed,
                           Consumer<Call> registerCall,
                           Consumer<Call> unregisterCall,
                           Runnable onClosed) {
        this.image = image;
        this.imageClient = Objects.requireNonNull(imageClient, "Image client cannot be null");
        this.resolver = Objects.requireNonNull(resolver, "Image resolver cannot be null");
        this.readerSlots = Objects.requireNonNull(readerSlots, "Reader slots cannot be null");
        this.clientClosed = Objects.requireNonNull(clientClosed, "Client closed callback cannot be null");
        this.registerCall = Objects.requireNonNull(registerCall, "Call register callback cannot be null");
        this.unregisterCall = Objects.requireNonNull(unregisterCall, "Call unregister callback cannot be null");
        this.onClosed = onClosed;
    }

    @Override
    public byte[] execute() {
        synchronized (lifecycleLock) {
            State observed = state.get();
            if (observed != State.NEW) {
                ImageFetchException.Reason reason = termination.get();
                if (reason != null) {
                    throw new ImageFetchException(reason);
                }
                throw new IllegalStateException("An image request can only be executed once");
            }
            state.set(State.RUNNING);
        }

        try {
            checkBeforeWork();
            HttpUrl url = resolver.resolve(image);
            acquireReaderSlot();
            try {
                Request request = new Request.Builder().url(url).get().build();
                Response response = executeCall(request);
                Call activeCall = currentCall.get();
                try (response) {
                    int status = response.code();
                    if (status < 200 || status >= 300) {
                        throw new ImageFetchException(ImageFetchException.Reason.HTTP_STATUS, status);
                    }
                    byte[] payload = readBody(response);
                    checkBeforeWork();
                    synchronized (lifecycleLock) {
                        if (termination.get() != null || closeRequested.get() || clientClosed.getAsBoolean()) {
                            if (clientClosed.getAsBoolean()) {
                                termination.compareAndSet(null, ImageFetchException.Reason.CLIENT_CLOSED);
                            }
                            throw terminationException();
                        }
                        state.set(State.SUCCEEDED);
                        return payload;
                    }
                } finally {
                    finishCall(activeCall);
                }
            } finally {
                readerSlots.release();
            }
        } catch (ImageFetchException exception) {
            throw fail(exception);
        } catch (IOException exception) {
            ImageFetchException.Reason reason = termination.get();
            if (reason == null && clientClosed.getAsBoolean()) {
                termination.compareAndSet(null, ImageFetchException.Reason.CLIENT_CLOSED);
                reason = termination.get();
            }
            if (reason == null && exception instanceof InterruptedIOException) {
                termination.compareAndSet(null, ImageFetchException.Reason.TIMEOUT);
                reason = termination.get();
            }
            if (reason == null) {
                reason = ImageFetchException.Reason.NETWORK;
            }
            throw fail(new ImageFetchException(reason, exception));
        } catch (RuntimeException exception) {
            throw fail(exception instanceof ImageFetchException imageException
                    ? imageException
                    : new ImageFetchException(ImageFetchException.Reason.NETWORK, exception));
        }
    }

    private byte[] readBody(Response response) throws IOException {
        ResponseBody body = response.body();
        if (body == null) {
            throw new ImageFetchException(ImageFetchException.Reason.INVALID_CONTENT);
        }
        long declaredLength = body.contentLength();
        if (declaredLength == 0) {
            throw new ImageFetchException(ImageFetchException.Reason.INVALID_CONTENT);
        }

        BufferedSource source = body.source();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] scratch = new byte[SCRATCH_BYTES];
        long total = 0;
        try {
            for (;;) {
                checkBeforeRead();
                int read = source.read(scratch, 0, scratch.length);
                if (read < 0) {
                    break;
                }
                if (read == 0) {
                    continue;
                }
                output.write(scratch, 0, read);
                total += read;
            }
            if (declaredLength >= 0 && total < declaredLength) {
                throw new ImageFetchException(ImageFetchException.Reason.TRUNCATED_BODY);
            }
            if (total == 0) {
                throw new ImageFetchException(ImageFetchException.Reason.INVALID_CONTENT);
            }
            return output.toByteArray();
        } catch (ImageFetchException exception) {
            throw exception;
        } catch (IOException exception) {
            if (declaredLength >= 0 && isUnexpectedEnd(exception)) {
                throw new ImageFetchException(ImageFetchException.Reason.TRUNCATED_BODY, exception);
            }
            throw exception;
        }
    }

    private static boolean isUnexpectedEnd(IOException exception) {
        if (exception instanceof EOFException) {
            return true;
        }
        String message = exception.getMessage();
        if (message == null) {
            return false;
        }
        String lower = message.toLowerCase(Locale.ROOT);
        return lower.contains("unexpected end") || lower.contains("unexpected eof");
    }

    private Response executeCall(Request request) throws IOException {
        checkBeforeWork();
        Call call = imageClient.newCall(request);
        registerCall.accept(call);
        currentCall.set(call);
        try {
            if (isCancellationSignalled()) {
                call.cancel();
                throw terminationException();
            }
            return call.execute();
        } catch (IOException | RuntimeException exception) {
            finishCall(call);
            throw exception;
        }
    }

    private void finishCall(Call call) {
        if (call == null) {
            return;
        }
        currentCall.compareAndSet(call, null);
        unregisterCall.accept(call);
    }

    private void acquireReaderSlot() {
        for (;;) {
            checkBeforeWork();
            try {
                if (readerSlots.tryAcquire(50, TimeUnit.MILLISECONDS)) {
                    if (isCancellationSignalled()) {
                        readerSlots.release();
                        throw terminationException();
                    }
                    return;
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                termination.compareAndSet(null, ImageFetchException.Reason.CANCELLED);
                throw new ImageFetchException(ImageFetchException.Reason.CANCELLED, exception);
            }
        }
    }

    private void checkBeforeRead() {
        checkBeforeWork();
    }

    private void checkBeforeWork() {
        if (clientClosed.getAsBoolean()) {
            requestClientClosed();
        }
        ImageFetchException.Reason reason = termination.get();
        if (reason != null) {
            throw new ImageFetchException(reason);
        }
    }

    private void requestClientClosed() {
        termination.compareAndSet(null, ImageFetchException.Reason.CLIENT_CLOSED);
        cancelCurrentCall();
        throw new ImageFetchException(ImageFetchException.Reason.CLIENT_CLOSED);
    }

    private void cancelCurrentCall() {
        Call call = currentCall.get();
        if (call != null) {
            call.cancel();
        }
    }

    private boolean isCancellationSignalled() {
        if (clientClosed.getAsBoolean()) {
            termination.compareAndSet(null, ImageFetchException.Reason.CLIENT_CLOSED);
        }
        return termination.get() != null;
    }

    private ImageFetchException.Reason effectiveReason(ImageFetchException.Reason original) {
        ImageFetchException.Reason requested = termination.get();
        return requested == null ? original : requested;
    }

    private ImageFetchException terminationException() {
        ImageFetchException.Reason reason = termination.get();
        return new ImageFetchException(reason == null
                ? ImageFetchException.Reason.CANCELLED : reason);
    }

    private ImageFetchException fail(ImageFetchException exception) {
        ImageFetchException.Reason reason = effectiveReason(exception.getReason());
        ImageFetchException finalException = reason == exception.getReason()
                ? exception
                : new ImageFetchException(reason, null, exception);
        synchronized (lifecycleLock) {
            State terminal = reason == ImageFetchException.Reason.CANCELLED
                    || reason == ImageFetchException.Reason.CLIENT_CLOSED
                    ? State.CANCELLED : State.FAILED;
            state.set(closeRequested.get() ? State.CLOSED : terminal);
        }
        notifyClosed();
        return finalException;
    }

    private RuntimeException fail(RuntimeException exception) {
        if (exception instanceof ImageFetchException imageException) {
            return fail(imageException);
        }
        return fail(new ImageFetchException(ImageFetchException.Reason.NETWORK, exception));
    }

    @Override
    public void cancel() {
        boolean notify = false;
        synchronized (lifecycleLock) {
            State current = state.get();
            if (current == State.SUCCEEDED || current == State.FAILED || current == State.CLOSED) {
                return;
            }
            termination.compareAndSet(null, ImageFetchException.Reason.CANCELLED);
            cancelCurrentCall();
            if (current == State.NEW) {
                state.set(State.CANCELLED);
                notify = true;
            }
        }
        if (notify) {
            notifyClosed();
        }
    }

    void closeFromClient() {
        boolean notify = false;
        synchronized (lifecycleLock) {
            State current = state.get();
            if (current == State.SUCCEEDED || current == State.FAILED || current == State.CLOSED) {
                close();
                return;
            }
            closeRequested.set(true);
            termination.compareAndSet(null, ImageFetchException.Reason.CLIENT_CLOSED);
            cancelCurrentCall();
            if (current == State.NEW) {
                state.set(State.CLOSED);
                notify = true;
            }
        }
        if (notify) {
            notifyClosed();
        }
    }

    @Override
    public boolean isCancelled() {
        ImageFetchException.Reason reason = termination.get();
        return reason == ImageFetchException.Reason.CANCELLED
                || reason == ImageFetchException.Reason.CLIENT_CLOSED;
    }

    @Override
    public void close() {
        boolean running = false;
        synchronized (lifecycleLock) {
            closeRequested.set(true);
            State current = state.get();
            if (current == State.NEW) {
                termination.compareAndSet(null, ImageFetchException.Reason.CANCELLED);
                state.set(State.CLOSED);
            } else if (current == State.RUNNING) {
                termination.compareAndSet(null, ImageFetchException.Reason.CANCELLED);
                cancelCurrentCall();
                running = true;
            } else if (current == State.SUCCEEDED
                    || current == State.CANCELLED || current == State.FAILED) {
                state.set(State.CLOSED);
            }
        }
        if (running) {
            return;
        }
        notifyClosed();
    }

    private void notifyClosed() {
        if (deregistered.compareAndSet(false, true) && onClosed != null) {
            onClosed.run();
        }
    }

    private enum State {
        NEW, RUNNING, SUCCEEDED, FAILED, CANCELLED, CLOSED
    }
}
