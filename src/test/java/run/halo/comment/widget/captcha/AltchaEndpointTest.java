package run.halo.comment.widget.captcha;

import static org.mockito.Mockito.*;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;
import run.halo.comment.widget.SettingConfigGetter;

class AltchaEndpointTest {
    @Test
    void servesUncachedChallengesOnlyWhenAltchaIsEnabled() {
        var settings = mock(SettingConfigGetter.class);
        var config = new SettingConfigGetter.CaptchaConfig().setEnable(true).setType(CaptchaType.ALTCHA);
        when(settings.getSecurityConfig()).thenReturn(Mono.just(
            new SettingConfigGetter.SecurityConfig().setCaptcha(config)));
        var client = WebTestClient.bindToRouterFunction(
            new AltchaEndpoint(new AltchaService(), settings).endpoint()).build();
        client.get().uri("/captcha/-/altcha").exchange().expectStatus().isOk()
            .expectHeader().valueEquals("Cache-Control", "no-store")
            .expectBody().jsonPath("$.parameters.algorithm").isEqualTo("PBKDF2/SHA-256")
            .jsonPath("$.parameters.expiresAt").isNumber()
            .jsonPath("$.parameters.keySignature").doesNotExist()
            .jsonPath("$.signature").isNotEmpty();
        config.setEnable(false);
        client.get().uri("/captcha/-/altcha").exchange().expectStatus().isNotFound();
        config.setEnable(true).setType(CaptchaType.TURNSTILE);
        client.get().uri("/captcha/-/altcha").exchange().expectStatus().isNotFound();
    }
}
