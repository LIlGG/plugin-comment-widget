package run.halo.comment.widget.upload;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import org.reactivestreams.Publisher;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.http.server.reactive.ServerHttpResponseDecorator;
import org.springframework.web.ErrorResponse;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Records the submission outcome before forwarding Halo's unchanged response. */
final class UploadSubmissionResponse extends ServerHttpResponseDecorator {

    private static final Set<Integer> REJECTED_STATUSES = Set.of(400, 401, 403, 404, 413, 422, 429);
    private final UploadLifecycleService lifecycle;
    private final ObjectMapper mapper;
    private final String submissionId;
    private final String targetKind;
    private final int memoryLimit;
    private final AtomicBoolean handled = new AtomicBoolean();

    UploadSubmissionResponse(
        ServerHttpResponse response,
        UploadLifecycleService lifecycle,
        ObjectMapper mapper,
        String submissionId,
        String targetKind,
        int memoryLimit
    ) {
        super(response);
        this.lifecycle = lifecycle;
        this.mapper = mapper;
        this.submissionId = submissionId;
        this.targetKind = targetKind;
        this.memoryLimit = memoryLimit;
    }

    @Override
    public Mono<Void> writeWith(Publisher<? extends DataBuffer> body) {
        return DataBufferUtils.join(Flux.from(body), memoryLimit).flatMap(this::recordAndWrite);
    }

    private Mono<Void> recordAndWrite(DataBuffer data) {
        byte[] bytes = UploadSubmissionFilter.read(data);
        return UploadSubmissionFilter.work(() -> {
            recordOutcome(bytes);
            handled.set(true);
        }).then(super.writeWith(Mono.just(bufferFactory().wrap(bytes))));
    }

    private void recordOutcome(byte[] bytes) {
        var status = getStatusCode();
        if (status == null) {
            lifecycle.unknown(submissionId);
            return;
        }
        if (status.is2xxSuccessful()) {
            bindCreatedTarget(bytes);
            return;
        }
        if (isRejected(status)) {
            lifecycle.fail(submissionId);
            return;
        }
        lifecycle.unknown(submissionId);
    }

    private void bindCreatedTarget(byte[] bytes) {
        try {
            var result = mapper.readTree(bytes);
            if (!targetKind.equals(result.path("kind").asText())) {
                throw new IllegalStateException("Invalid comment response");
            }
            String name = result.path("metadata").path("name").asText();
            if (name.isBlank()) {
                throw new IllegalStateException("Invalid comment response");
            }
            lifecycle.bind(submissionId, targetKind, name);
        } catch (IOException error) {
            throw new IllegalStateException(error);
        }
    }

    @Override
    public Mono<Void> writeAndFlushWith(Publisher<? extends Publisher<? extends DataBuffer>> body) {
        return writeWith(Flux.from(body).concatMap(Flux::from));
    }

    Mono<Void> complete() {
        if (handled.get()) {
            return Mono.empty();
        }
        return cancelled();
    }

    Mono<Void> failed(Throwable error) {
        return UploadSubmissionFilter.work(() -> recordFailure(error));
    }

    private void recordFailure(Throwable error) {
        // Preserve the original rule: an error after handling remains uncertain.
        if (handled.get()) {
            lifecycle.unknown(submissionId);
            return;
        }
        if (error instanceof ErrorResponse responseError) {
            if (isRejected(responseError.getStatusCode())) {
                lifecycle.fail(submissionId);
                return;
            }
        }
        lifecycle.unknown(submissionId);
    }

    Mono<Void> cancelled() {
        return UploadSubmissionFilter.work(() -> lifecycle.unknown(submissionId));
    }

    private static boolean isRejected(HttpStatusCode status) {
        return REJECTED_STATUSES.contains(status.value());
    }
}
