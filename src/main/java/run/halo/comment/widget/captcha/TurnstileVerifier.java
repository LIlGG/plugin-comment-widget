package run.halo.comment.widget.captcha;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.app.extension.Secret;
import run.halo.comment.widget.SettingConfigGetter;

@Component
public class TurnstileVerifier {
    // Cloudflare test keys may return a different action or omit it entirely.
    private static final String TEST_SECRET = "1x0000000000000000000000000000000AA";
    private final ReactiveExtensionClient client;
    private final WebClient webClient;

    @Autowired
    public TurnstileVerifier(ReactiveExtensionClient client) {
        this(client, WebClient.builder().baseUrl("https://challenges.cloudflare.com")
            .codecs(codecs -> codecs.defaultCodecs().maxInMemorySize(16 * 1024)).build());
    }

    TurnstileVerifier(ReactiveExtensionClient client, WebClient webClient) {
        this.client = client;
        this.webClient = webClient;
    }

    record VerificationResponse(Boolean success, String action) {}

    private String secretKey(Secret secret) {
        if (secret.getStringData() != null && secret.getStringData().containsKey("secretKey")) {
            return secret.getStringData().get("secretKey");
        }
        var bytes = secret.getData() == null ? null : secret.getData().get("secretKey");
        return bytes == null ? null : new String(bytes, StandardCharsets.UTF_8);
    }

    public Mono<Boolean> verify(String token, SettingConfigGetter.CaptchaConfig config) {
        if (StringUtils.isBlank(token) || token.length() > 2048
            || StringUtils.isBlank(config.getTurnstileSiteKey())
            || StringUtils.isBlank(config.getTurnstileSecretRef())) {
            return Mono.just(false);
        }
        return client.fetch(Secret.class, config.getTurnstileSecretRef())
            .mapNotNull(this::secretKey)
            .filter(StringUtils::isNotBlank)
            .flatMap(secret -> webClient.post().uri("/turnstile/v0/siteverify")
                .bodyValue(Map.of("secret", secret, "response", token))
                .retrieve().bodyToMono(VerificationResponse.class)
                .map(body -> Boolean.TRUE.equals(body.success())
                    && ("comment".equals(body.action())
                        || TEST_SECRET.equals(secret))))
            .defaultIfEmpty(false)
            .timeout(Duration.ofSeconds(10))
            .onErrorReturn(false);
    }
}
