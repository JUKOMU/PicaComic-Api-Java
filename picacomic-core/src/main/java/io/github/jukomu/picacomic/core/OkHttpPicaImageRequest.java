package io.github.jukomu.picacomic.core;

import io.github.jukomu.picacomic.api.client.PicaImageRequest;
import io.github.jukomu.picacomic.api.exception.ImageFetchException;
import io.github.jukomu.picacomic.api.model.PicaImage;
import okhttp3.Call;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.BufferedSource;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.time.Duration;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.Objects;

/**
 * 图片专用 OkHttp 请求句柄。它不继承 API client 的 request、Cookie 或拦截器。
 */
final class OkHttpPicaImageRequest implements PicaImageRequest {

    private static final int MAX_REDIRECTS = 3;
    private static final int SCRATCH_BYTES = 8 * 1024;
    private static final Set<String> RASTER_MEDIA_TYPES = Set.of(
            "image/jpeg", "image/png", "image/webp", "image/gif");

    private final PicaImage image;
    private final OkHttpClient imageClient;
    private final ImageLocatorResolver resolver;
    private final Semaphore readerSlots;
    private final Duration timeout;
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
                           Duration timeout,
                           BooleanSupplier clientClosed,
                           Consumer<Call> registerCall,
                           Consumer<Call> unregisterCall,
                           Runnable onClosed) {
        this.image = image;
        this.imageClient = imageClient;
        this.resolver = resolver;
        this.readerSlots = readerSlots;
        this.timeout = timeout;
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
            long deadline = deadlineNanos();
            activeDeadline = deadline;
            checkBeforeWork(deadline);
            HttpUrl current = resolver.resolve(image);
            Set<HttpUrl> seen = new HashSet<>();
            int redirects = 0;

            for (;;) {
                checkBeforeWork(deadline);
                if (!seen.add(current)) {
                    throw new ImageFetchException(ImageFetchException.Reason.REDIRECT_REJECTED);
                }
                acquireReaderSlot(deadline);
                try {
                    Request request = new Request.Builder()
                            .url(current)
                            .get()
                            .header("Accept-Encoding", "identity")
                            .build();
                    Response response = executeCall(request, deadline);
                    Call activeCall = currentCall.get();
                    try (response) {
                        int status = response.code();
                        if (isRedirect(status)) {
                            if (redirects >= MAX_REDIRECTS) {
                                throw new ImageFetchException(ImageFetchException.Reason.REDIRECT_REJECTED);
                            }
                            String location = response.header("Location");
                            HttpUrl target = resolver.resolveRedirect(current, location);
                            if (seen.contains(target)) {
                                throw new ImageFetchException(ImageFetchException.Reason.REDIRECT_REJECTED);
                            }
                            current = target;
                            redirects++;
                            continue;
                        }
                        if (status != 200) {
                            throw new ImageFetchException(ImageFetchException.Reason.HTTP_STATUS, status);
                        }
                        validateContentEncoding(response.headers("Content-Encoding"));
                        validateMediaType(response.headers("Content-Type"));
                        byte[] payload = readBody(response, deadline);
                        if (termination.get() == null && clientClosed.getAsBoolean()) {
                            termination.compareAndSet(null, ImageFetchException.Reason.CLIENT_CLOSED);
                        }
                        if (termination.get() != null) {
                            throw terminationException();
                        }
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
            }
        } catch (ImageFetchException exception) {
            throw fail(exception);
        } catch (IOException exception) {
            ImageFetchException.Reason reason = termination.get();
            if (reason == null && clientClosed.getAsBoolean()) {
                termination.compareAndSet(null, ImageFetchException.Reason.CLIENT_CLOSED);
                reason = termination.get();
            }
            if (reason == null && (deadlineReached() || exception instanceof InterruptedIOException)) {
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

    private byte[] readBody(Response response, long deadline) throws IOException {
        ResponseBody body = response.body();
        if (body == null) {
            throw new ImageFetchException(ImageFetchException.Reason.INVALID_CONTENT);
        }
        long length = body.contentLength();
        if (length == 0) {
            throw new ImageFetchException(ImageFetchException.Reason.INVALID_CONTENT);
        }

        BufferedSource source = body.source();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] scratch = new byte[SCRATCH_BYTES];
        try {
            if (length >= 0) {
                long remaining = length;
                while (remaining > 0) {
                    checkBeforeRead(deadline);
                    applyReadTimeout(source, deadline);
                    int read = source.read(scratch, 0, (int) Math.min(scratch.length, remaining));
                    if (read < 0) {
                        throw new ImageFetchException(ImageFetchException.Reason.TRUNCATED_BODY);
                    }
                    if (read == 0) {
                        continue;
                    }
                    output.write(scratch, 0, read);
                    remaining -= read;
                }
            } else {
                for (;;) {
                    checkBeforeRead(deadline);
                    applyReadTimeout(source, deadline);
                    int read = source.read(scratch, 0, scratch.length);
                    if (read < 0) {
                        break;
                    }
                    if (read == 0) {
                        continue;
                    }
                    output.write(scratch, 0, read);
                }
            }
            if (output.size() == 0) {
                throw new ImageFetchException(ImageFetchException.Reason.INVALID_CONTENT);
            }
            return output.toByteArray();
        } catch (ImageFetchException exception) {
            throw exception;
        } catch (IOException exception) {
            if (length >= 0 && isUnexpectedEnd(exception)) {
                throw new ImageFetchException(ImageFetchException.Reason.TRUNCATED_BODY, exception);
            }
            throw exception;
        }
    }

    private static void applyReadTimeout(BufferedSource source, long deadline) {
        long remaining = deadline == Long.MAX_VALUE ? Long.MAX_VALUE : deadline - System.nanoTime();
        if (remaining <= 0) {
            throw new ImageFetchException(ImageFetchException.Reason.TIMEOUT);
        }
        source.timeout().clearTimeout();
        source.timeout().timeout(Math.max(1L, remaining), TimeUnit.NANOSECONDS);
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

    private void validateMediaType(java.util.List<String> values) {
        if (values.size() != 1) {
            throw new ImageFetchException(ImageFetchException.Reason.UNSUPPORTED_MEDIA_TYPE);
        }
        MediaType parsed;
        try {
            parsed = MediaType.parse(values.get(0));
        } catch (IllegalArgumentException exception) {
            throw new ImageFetchException(ImageFetchException.Reason.UNSUPPORTED_MEDIA_TYPE, exception);
        }
        if (parsed == null) {
            throw new ImageFetchException(ImageFetchException.Reason.UNSUPPORTED_MEDIA_TYPE);
        }
        String mediaType = (parsed.type() + "/" + parsed.subtype()).toLowerCase(Locale.ROOT);
        if (!RASTER_MEDIA_TYPES.contains(mediaType)) {
            throw new ImageFetchException(ImageFetchException.Reason.UNSUPPORTED_MEDIA_TYPE);
        }
    }

    private static void validateContentEncoding(java.util.List<String> values) {
        if (values.size() > 1 || (values.size() == 1
                && !"identity".equalsIgnoreCase(values.get(0).trim()))) {
            throw new ImageFetchException(ImageFetchException.Reason.UNSUPPORTED_CONTENT_ENCODING);
        }
    }

    private static boolean isRedirect(int status) {
        return status == 301 || status == 302 || status == 303 || status == 307 || status == 308;
    }

    private Response executeCall(Request request, long deadline) throws IOException {
        checkBeforeWork(deadline);
        Call call = imageClient.newCall(request);
        registerCall.accept(call);
        currentCall.set(call);
        try {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                requestTimeout();
            }
            call.timeout().timeout(Math.max(1L, remaining), TimeUnit.NANOSECONDS);
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

    private void acquireReaderSlot(long deadline) {
        for (;;) {
            checkBeforeWork(deadline);
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                requestTimeout();
            }
            try {
                if (readerSlots.tryAcquire(Math.min(TimeUnit.MILLISECONDS.toNanos(50L), Math.max(1L, remaining)),
                        TimeUnit.NANOSECONDS)) {
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

    private void checkBeforeRead(long deadline) {
        checkBeforeWork(deadline);
    }

    private void checkBeforeWork(long deadline) {
        if (clientClosed.getAsBoolean()) {
            requestClientClosed();
        }
        ImageFetchException.Reason reason = termination.get();
        if (reason != null) {
            throw new ImageFetchException(reason);
        }
        if (deadline - System.nanoTime() <= 0) {
            requestTimeout();
        }
    }

    private void cancelCurrentCall() {
        Call call = currentCall.get();
        if (call != null) {
            call.cancel();
        }
    }

    private void requestTimeout() {
        termination.compareAndSet(null, ImageFetchException.Reason.TIMEOUT);
        cancelCurrentCall();
        throw new ImageFetchException(ImageFetchException.Reason.TIMEOUT);
    }

    private void requestClientClosed() {
        termination.compareAndSet(null, ImageFetchException.Reason.CLIENT_CLOSED);
        Call call = currentCall.get();
        if (call != null) {
            call.cancel();
        }
        throw new ImageFetchException(ImageFetchException.Reason.CLIENT_CLOSED);
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

    private long deadlineNanos() {
        long now = System.nanoTime();
        long duration;
        try {
            duration = timeout.toNanos();
        } catch (ArithmeticException overflow) {
            return Long.MAX_VALUE;
        }
        if (duration >= Long.MAX_VALUE - now) {
            return Long.MAX_VALUE;
        }
        return now + duration;
    }

    private volatile long activeDeadline;

    private boolean deadlineReached() {
        return activeDeadline > 0 && activeDeadline != Long.MAX_VALUE
                && activeDeadline - System.nanoTime() <= 0;
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
            Call call = currentCall.get();
            if (call != null) {
                call.cancel();
            }
            if (current == State.NEW) {
                state.set(State.CANCELLED);
                notify = true;
            }
        }
        if (notify) {
            notifyClosed();
        }
    }

    /**
     * 由所属 client 在 close 时调用；不属于 api module 的额外公共契约。
     */
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
            Call call = currentCall.get();
            if (call != null) {
                call.cancel();
            }
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
                Call call = currentCall.get();
                if (call != null) {
                    call.cancel();
                }
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
