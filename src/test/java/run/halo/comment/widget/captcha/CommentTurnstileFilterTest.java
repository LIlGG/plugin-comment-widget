package run.halo.comment.widget.captcha;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.web.server.context.ServerSecurityContextRepository;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;
import run.halo.comment.widget.SettingConfigGetter;

class CommentTurnstileFilterTest {
    @Test
    void rejectsUnverifiedCommentsAndRepliesWithoutCallingHandler() {
        for (var path : new String[]{"/apis/api.halo.run/v1alpha1/comments",
            "/apis/api.halo.run/v1alpha1/comments/example/reply"}) {
            var settings = mock(SettingConfigGetter.class);
            var verifier = mock(TurnstileVerifier.class);
            var context = mock(ServerSecurityContextRepository.class);
            var chain = mock(WebFilterChain.class);
            var config = new SettingConfigGetter.CaptchaConfig()
                .setAnonymousCommentCaptcha(true).setType(CaptchaType.TURNSTILE);
            when(settings.getSecurityConfig()).thenReturn(Mono.just(new SettingConfigGetter.SecurityConfig().setCaptcha(config)));
            when(context.load(any())).thenReturn(Mono.empty());
            when(verifier.verify(isNull(), eq(config))).thenReturn(Mono.just(false));
            var handlerCalled = new java.util.concurrent.atomic.AtomicBoolean();
            when(chain.filter(any())).thenReturn(Mono.fromRunnable(() -> handlerCalled.set(true)));
            var filter = new CommentCaptchaFilter(settings, mock(CaptchaManager.class), verifier,
                mock(CaptchaCookieResolverImpl.class), context);
            var exchange = MockServerWebExchange.from(MockServerHttpRequest.post(path));
            filter.filter(exchange, chain).block();
            assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(handlerCalled).isFalse();
            assertThat(exchange.getResponse().getBodyAsString().block()).contains("Turnstile Verification");
        }
    }
}
