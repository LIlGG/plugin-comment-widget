package run.halo.comment.widget;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.codec.ServerCodecConfigurer;
import org.springframework.http.codec.multipart.DefaultPartHttpMessageReader;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.http.codec.multipart.MultipartHttpMessageReader;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.http.server.reactive.MockServerHttpResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.adapter.DefaultServerWebExchange;
import org.springframework.web.server.i18n.AcceptHeaderLocaleContextResolver;
import org.springframework.web.server.session.DefaultWebSessionManager;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import run.halo.app.core.extension.attachment.Attachment;
import run.halo.app.core.extension.service.AttachmentService;
import run.halo.app.extension.Metadata;
import run.halo.comment.widget.upload.CommentUpload;
import run.halo.comment.widget.upload.UploadLifecycleService;

class UploadMediaEndpointTest {

    private final UploadLifecycleService lifecycle = mock(UploadLifecycleService.class);
    private final AttachmentService attachments = mock(AttachmentService.class);
    private final UploadMediaEndpoint endpoint = new UploadMediaEndpoint(
        lifecycle,
        null,
        attachments,
        null,
        null
    );
    private final SettingConfigGetter.EditorConfig config = new SettingConfigGetter.EditorConfig();
    private final ServerCodecConfigurer codecs = ServerCodecConfigurer.create();
    private final Attachment attachment = new Attachment();
    private FilePart receivedFile;
    private int receivedBytes;

    @BeforeEach
    void setUp() {
        config.setEnableUpload(true);
        config.getUpload().getAttachment().setAttachmentPolicy("selected-policy");
        config.getUpload().getAttachment().setAttachmentGroup("selected-group");
        var record = new CommentUpload();
        var metadata = new Metadata();
        metadata.setName("upload-id");
        record.setMetadata(metadata);
        record.setSpec(new CommentUpload.Spec());
        when(lifecycle.begin("draft", "alice")).thenReturn(record);
        when(attachments.getPermalink(attachment)).thenReturn(Mono.just(URI.create("/image.avif")));
        when(
            attachments.upload(
                eq("alice"),
                eq("selected-policy"),
                eq("selected-group"),
                any(FilePart.class),
                any()
            )
        ).thenAnswer(invocation -> receive(invocation.getArgument(3)));
    }

    @Test
    void delegatesLargeAvifToSelectedStoragePolicyWithoutRewriting() {
        var result = upload(request(11 * 1024 * 1024, 1)).block(Duration.ofSeconds(10));
        assertThat(result).hasSize(1);
        assertThat(receivedBytes).isEqualTo(11 * 1024 * 1024);
        assertThat(receivedFile.filename()).isEqualTo("original.avif");
        assertThat(receivedFile.headers().getContentType().toString()).isEqualTo("image/avif");
        verify(lifecycle).uploaded("upload-id", attachment, "/image.avif");
        assertTemporaryPartDeleted();
    }

    @Test
    void acceptsMoreThanTenPartsWhenHostAllowsThem() {
        assertThat(upload(request(1, 11)).block(Duration.ofSeconds(10))).hasSize(11);
    }

    @Test
    void preservesStoragePolicyRejectionAndCleansTemporaryFile() {
        var rejection = new ResponseStatusException(
            HttpStatus.BAD_REQUEST,
            "Storage policy rejects type"
        );
        when(attachments.upload(any(), any(), any(), any(FilePart.class), any())).thenAnswer(
            invocation -> {
                receivedFile = invocation.getArgument(3);
                return Mono.error(rejection);
            }
        );
        assertThatThrownBy(() -> upload(request(300 * 1024, 1)).block()).isSameAs(rejection);
        assertTemporaryPartDeleted();
    }

    @Test
    void respectsHostMultipartSizeLimit() {
        var reader = new DefaultPartHttpMessageReader();
        reader.setMaxInMemorySize(32);
        reader.setMaxDiskUsagePerPart(64);
        codecs.defaultCodecs().multipartReader(new MultipartHttpMessageReader(reader));
        assertThatThrownBy(() -> upload(request(300 * 1024, 1)).block()).isInstanceOfSatisfying(
            ResponseStatusException.class,
            error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE)
        );
        verifyNoInteractions(attachments, lifecycle);
    }

    @Test
    void cleansTemporaryFileWhenUploadIsCancelled() throws Exception {
        var started = new CountDownLatch(1);
        when(attachments.upload(any(), any(), any(), any(FilePart.class), any())).thenAnswer(
            invocation -> {
                receivedFile = invocation.getArgument(3);
                return Mono.<Attachment>never().doOnSubscribe(subscription -> started.countDown());
            }
        );
        var subscription = upload(request(300 * 1024, 1)).subscribe();
        try {
            assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
        } finally {
            subscription.dispose();
        }
        await().atMost(Duration.ofSeconds(5)).untilAsserted(this::assertTemporaryPartDeleted);
    }

    private Mono<Attachment> receive(FilePart file) {
        receivedFile = file;
        return DataBufferUtils.join(file.content()).map(buffer -> {
            receivedBytes = buffer.readableByteCount();
            DataBufferUtils.release(buffer);
            return attachment;
        });
    }

    private void assertTemporaryPartDeleted() {
        assertThatThrownBy(() -> receivedFile.content().then().block()).isNotNull();
    }

    @SuppressWarnings("unchecked")
    private Mono<List<UploadMediaEndpoint.UploadedImage>> upload(ServerRequest request) {
        Mono<List<UploadMediaEndpoint.UploadedImage>> result = ReflectionTestUtils.invokeMethod(
            endpoint,
            "readAndUpload",
            request,
            "draft",
            config
        );
        return result.contextWrite(
            ReactiveSecurityContextHolder.withAuthentication(
                UsernamePasswordAuthenticationToken.authenticated("alice", "", List.of())
            )
        );
    }

    private ServerRequest request(int size, int count) {
        String part =
            "--boundary\r\nContent-Disposition: form-data; name=\"files\"; " +
            "filename=\"original.avif\"\r\nContent-Type: image/avif\r\n\r\n" +
            "x".repeat(size) +
            "\r\n";
        byte[] body = (part.repeat(count) + "--boundary--\r\n").getBytes(StandardCharsets.UTF_8);
        var request = MockServerHttpRequest.post("/upload")
            .header("Content-Type", "multipart/form-data; boundary=boundary")
            .body(
                Flux.range(0, (body.length + 8191) / 8192).map(index ->
                    DefaultDataBufferFactory.sharedInstance.wrap(
                        Arrays.copyOfRange(
                            body,
                            index * 8192,
                            Math.min(body.length, (index + 1) * 8192)
                        )
                    )
                )
            );
        return ServerRequest.create(
            new DefaultServerWebExchange(
                request,
                new MockServerHttpResponse(),
                new DefaultWebSessionManager(),
                codecs,
                new AcceptHeaderLocaleContextResolver()
            ),
            codecs.getReaders()
        );
    }
}
