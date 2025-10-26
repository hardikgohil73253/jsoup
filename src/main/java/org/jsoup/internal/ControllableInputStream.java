package org.jsoup.internal;

import org.jsoup.Progress;
import org.jsoup.helper.Validate;
import org.jspecify.annotations.Nullable;

import java.io.BufferedInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;

import static org.jsoup.internal.SharedConstants.DefaultBufferSize;

public class ControllableInputStream extends FilterInputStream {
    private final SimpleBufferedInput buff;
    private int maxSize;
    private long startTime;
    private long timeout = 0; // optional max time of request
    private int remaining;
    private int markPos;
    private boolean interrupted;
    private boolean allowClose = true;

    private @Nullable Progress<?> progress;
    private @Nullable Object progressContext;
    private int contentLength = -1;
    private int readPos = 0;

    private ControllableInputStream(SimpleBufferedInput in, int maxSize) {
        super(in);
        Validate.isTrue(maxSize >= 0);
        buff = in;
        this.maxSize = maxSize;
        remaining = maxSize;
        markPos = -1;
        startTime = System.nanoTime();
    }

    public static ControllableInputStream wrap(@Nullable InputStream in, int maxSize) {
        if (in instanceof ControllableInputStream)
            return (ControllableInputStream) in;
        else
            return new ControllableInputStream(new SimpleBufferedInput(in), maxSize);
    }

    public static ControllableInputStream wrap(InputStream in, int bufferSize, int maxSize) {
        return wrap(in, maxSize);
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        if (readPos == 0) emitProgress(); // emits a progress

        boolean capped = maxSize != 0;
        if (interrupted || capped && remaining <= 0)
            return -1;
        if (Thread.currentThread().isInterrupted()) {
            interrupted = true;
            return -1;
        }

        if (capped && len > remaining)
            len = remaining; // don't read more than desired, even if available

        while (true) {
            if (expired())
                throw new SocketTimeoutException("Read timeout");

            try {
                final int read = super.read(b, off, len);
                if (read == -1) {
                    contentLength = readPos;
                } else {
                    remaining -= read;
                    readPos += read;
                }
                emitProgress();
                return read;
            } catch (SocketTimeoutException e) {
                if (expired() || timeout == 0)
                    throw e;
            }
        }
    }

    public static ByteBuffer readToByteBuffer(InputStream in, int max) throws IOException {
        Validate.isTrue(max >= 0, "maxSize must be 0 (unlimited) or larger");
        Validate.notNull(in);
        final boolean capped = max > 0;
        final byte[] readBuf = SimpleBufferedInput.BufferPool.borrow();
        final int outSize = capped ? Math.min(max, DefaultBufferSize) : DefaultBufferSize;
        ByteBuffer outBuf = ByteBuffer.allocate(outSize);

        try {
            int remaining = max;
            int read;
            while ((read = in.read(readBuf, 0, capped ? Math.min(remaining, DefaultBufferSize) : DefaultBufferSize)) != -1) {
                if (outBuf.remaining() < read) {
                    int newCapacity = (int) Math.max(outBuf.capacity() * 1.5, outBuf.capacity() + read);
                    ByteBuffer newBuffer = ByteBuffer.allocate(newCapacity);
                    outBuf.flip();
                    newBuffer.put(outBuf);
                    outBuf = newBuffer;
                }
                outBuf.put(readBuf, 0, read);
                if (capped) {
                    remaining -= read;
                    if (remaining <= 0) break;
                }
            }
            outBuf.flip();
            return outBuf;
        } finally {
            SimpleBufferedInput.BufferPool.release(readBuf);
        }
    }

    @Override public void reset() throws IOException {
        super.reset();
        remaining = maxSize - markPos;
        readPos = markPos;
    }

    @Override public void mark(int readlimit) {
        super.mark(readlimit);
        markPos = maxSize - remaining;
    }

    public boolean baseReadFully() {
        return buff.baseReadFully();
    }

    public int max() {
        return maxSize;
    }

    public void max(int newMax) {
        remaining += newMax - maxSize;
        maxSize = newMax;
    }

    public void allowClose(boolean allowClose) {
        this.allowClose = allowClose;
    }

    @Override public void close() throws IOException {
        if (allowClose) super.close();
    }

    public ControllableInputStream timeout(long startTimeNanos, long timeoutMillis) {
        this.startTime = startTimeNanos;
        this.timeout = timeoutMillis * 1000000;
        return this;
    }

    private void emitProgress() {
        if (progress == null) return;
        float percent = contentLength > 0 ? Math.min(100f, readPos * 100f / contentLength) : 0;
        ((Progress<Object>) progress).onProgress(readPos, contentLength, percent, progressContext);
        if (percent == 100.0f) progress = null;
    }
    

    public <P> ControllableInputStream onProgress(int contentLength, Progress<P> callback, P context) {
        Validate.notNull(callback);
        Validate.notNull(context);
        this.contentLength = contentLength;
        this.progress = callback;
        this.progressContext = context;
        return this;
    }
    

    private boolean expired() {
        if (timeout == 0)
            return false;

        final long now = System.nanoTime();
        final long dur = now - startTime;
        return (dur > timeout);
    }

    public BufferedInputStream inputStream() {
        return new BufferedInputStream(buff);
    }
}