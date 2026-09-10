package run.halo.comment.widget.captcha;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.app.extension.Secret;
import run.halo.comment.widget.SettingConfigGetter;

class TurnstileVerifierTest {
    private final ReactiveExtensionClient client = mock(ReactiveExtensionClient.class);
    private final SettingConfigGetter.CaptchaConfig config = new SettingConfigGetter.CaptchaConfig()
        .setTurnstileSiteKey("site-key").setTurnstileSecretRef("turnstile");

    private TurnstileVerifier verifier(String response) {
        return new TurnstileVerifier(client, WebClient.builder().exchangeFunction(request -> {
            assertThat(request.url().getPath()).isEqualTo("/turnstile/v0/siteverify");
            return Mono.just(ClientResponse.create(HttpStatus.OK)
                .header("Content-Type", "application/json").body(response).build());
        }).build());
    }

    private void secret() {
        var secret = new Secret();
        secret.setStringData(Map.of("secretKey", "test-secret"));
        when(client.fetch(Secret.class, "turnstile")).thenReturn(Mono.just(secret));
    }

    @Test
    void requiresSuccessfulVerificationAndMatchingAction() {
        secret();
        assertThat(verifier("{\"success\":true,\"action\":\"comment\"}").verify("token", config).block()).isTrue();
        assertThat(verifier("{\"success\":false}").verify("token", config).block()).isFalse();
        assertThat(verifier("{\"success\":true,\"action\":\"login\"}").verify("token", config).block()).isFalse();
    }

    @Test
    void acceptsOfficialTestResponseOnlyWithOfficialTestSecret() {
        secret();
        assertThat(verifier("{\"success\":true,\"action\":\"test\"}").verify("token", config).block()).isFalse();
        var secret = new Secret();
        secret.setData(Map.of("secretKey", "1x0000000000000000000000000000000AA".getBytes(StandardCharsets.UTF_8)));
        when(client.fetch(Secret.class, "turnstile")).thenReturn(Mono.just(secret));
        assertThat(verifier("{\"success\":true}").verify("token", config).block()).isTrue();
    }

    @Test
    void rejectsMissingAndOversizedTokensBeforeCallingCloudflare() {
        var verifier = verifier("{}");
        assertThat(verifier.verify(null, config).block()).isFalse();
        assertThat(verifier.verify(" ", config).block()).isFalse();
        assertThat(verifier.verify("x".repeat(2049), config).block()).isFalse();
        verifyNoInteractions(client);
    }

    @Test
    void rejectsMissingSecretAndInvalidResponses() {
        when(client.fetch(Secret.class, "turnstile")).thenReturn(Mono.empty());
        assertThat(verifier("{}").verify("token", config).block()).isFalse();
        secret();
        assertThat(verifier("{}").verify("token", config).block()).isFalse();
        assertThat(verifier("invalid-json").verify("token", config).block()).isFalse();
    }

    @Test
    void failsClosedWhenCloudflareIsUnavailable() {
        secret();
        var verifier = new TurnstileVerifier(client, WebClient.builder()
            .exchangeFunction(request -> Mono.error(new IllegalStateException("offline"))).build());
        assertThat(verifier.verify("token", config).block()).isFalse();
    }
}
