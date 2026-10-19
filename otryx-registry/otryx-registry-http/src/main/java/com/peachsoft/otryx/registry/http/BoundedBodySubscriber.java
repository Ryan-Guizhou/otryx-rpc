package com.peachsoft.otryx.registry.http;

import com.peachsoft.otryx.registry.RegistrySecurityException;
import com.peachsoft.otryx.registry.RegistrySecurityFailure;
import java.io.ByteArrayOutputStream;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;

/**
 * 在复制任何网络 chunk 之前检查累计字节预算，并在超限或取消时释放自有缓冲。
 *
 * @Author OpenAI
 * @Version 1.0.0-SNAPSHOT
 * @CreateTime 2026/10/19 11:41
 */
final class BoundedBodySubscriber implements HttpResponse.BodySubscriber<byte[]> {
    private static final int INITIAL_BUFFER_BYTES = 8192;
    private final int maximumBytes;
    private final CompletableFuture<byte[]> body = new CompletableFuture<>();
    private ByteArrayOutputStream bytes;
    private Flow.Subscription subscription;
    private int received;
    private boolean terminated;

    BoundedBodySubscriber(int maximumBytes) {
        this.maximumBytes = maximumBytes;
        this.bytes = new ByteArrayOutputStream(Math.min(maximumBytes, INITIAL_BUFFER_BYTES));
    }

    @Override
    public CompletionStage<byte[]> getBody() {
        return body;
    }

    @Override
    public synchronized void onSubscribe(Flow.Subscription value) {
        if (subscription != null || terminated) {
            value.cancel();
            return;
        }
        subscription = value;
        value.request(1);
    }

    @Override
    public synchronized void onNext(List<ByteBuffer> chunks) {
        if (terminated) {
            return;
        }
        long incoming = 0;
        for (ByteBuffer chunk : chunks) {
            incoming += chunk.remaining();
            if (incoming > maximumBytes - received) {
                reject(new RegistrySecurityException(RegistrySecurityFailure.RESPONSE_LIMIT_EXCEEDED));
                return;
            }
        }
        for (ByteBuffer chunk : chunks) {
            int size = chunk.remaining();
            byte[] part = new byte[size];
            chunk.get(part);
            bytes.write(part, 0, size);
            received += size;
        }
        subscription.request(1);
    }

    @Override
    public synchronized void onError(Throwable failure) {
        reject(failure);
    }

    @Override
    public synchronized void onComplete() {
        if (!terminated) {
            terminated = true;
            byte[] result = bytes.toByteArray();
            bytes = null;
            body.complete(result);
        }
    }

    synchronized void reject(Throwable failure) {
        if (!terminated) {
            terminated = true;
            bytes = null;
            if (subscription != null) {
                subscription.cancel();
            }
            body.completeExceptionally(failure);
        }
    }

    synchronized void cancel() {
        reject(new java.util.concurrent.CancellationException("Registry HTTP body cancelled"));
    }
}
