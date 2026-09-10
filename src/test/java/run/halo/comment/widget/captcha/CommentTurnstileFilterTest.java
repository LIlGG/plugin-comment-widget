package run.halo.comment.widget.captcha;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.web.server.context.ServerSecurityContextRepository;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;
import run.halo.comment.widget.SettingConfigGetter;

class CommentTurnstileFilterTest {
    @ParameterizedTest
    @EnumSource(TurnstileVerifier.Result.class)
    void handlesVerificationResultsForCommentsAndReplies(TurnstileVerifier.Result result) {
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
            when(verifier.verify(isNull(), eq(config))).thenReturn(Mono.just(result));
            var handlerCalled = new java.util.concurrent.atomic.AtomicBoolean();
            when(chain.filter(any())).thenReturn(Mono.fromRunnable(() -> handlerCalled.set(true)));
            var filter = new CommentCaptchaFilter(settings, mock(CaptchaManager.class), verifier,
                mock(CaptchaCookieResolverImpl.class), context);
            var exchange = MockServerWebExchange.from(MockServerHttpRequest.post(path));
            filter.filter(exchange, chain).block();
            if (result == TurnstileVerifier.Result.VALID) {
                assertThat(handlerCalled).isTrue();
                continue;
            }
            assertThat(handlerCalled).isFalse();
            var expectedStatus = HttpStatus.SERVICE_UNAVAILABLE;
            var expectedDetail = "人机验证服务暂不可用";
            if (result == TurnstileVerifier.Result.INVALID) {
                expectedStatus = HttpStatus.FORBIDDEN;
                expectedDetail = "人机验证未通过";
            } else if (result == TurnstileVerifier.Result.CONFIGURATION_ERROR) {
                expectedDetail = "人机验证配置异常";
            }
            assertThat(exchange.getResponse().getStatusCode()).isEqualTo(expectedStatus);
            assertThat(exchange.getResponse().getBodyAsString().block()).contains(expectedDetail);
        }
    }
}
