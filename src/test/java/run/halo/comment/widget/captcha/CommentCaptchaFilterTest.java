package run.halo.comment.widget.captcha;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.HttpCookie;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.web.server.context.ServerSecurityContextRepository;
import reactor.core.publisher.Mono;
import run.halo.comment.widget.SettingConfigGetter;

class CommentCaptchaFilterTest {
    final SettingConfigGetter settings = mock(SettingConfigGetter.class);
    final CaptchaManager manager = mock(CaptchaManager.class);
    final CaptchaCookieResolverImpl cookies = new CaptchaCookieResolverImpl();
    final ServerSecurityContextRepository contexts = mock(ServerSecurityContextRepository.class);
    final CommentCaptchaFilter filter = new CommentCaptchaFilter(settings, manager, cookies, contexts);

    void configure(boolean anonymous, boolean authenticated, boolean loggedIn) {
        var captcha = new SettingConfigGetter.CaptchaConfig()
            .setEnable(anonymous || authenticated)
            .setAudience(anonymous && authenticated
                ? SettingConfigGetter.CaptchaConfig.CaptchaAudience.ALL
                : authenticated ? SettingConfigGetter.CaptchaConfig.CaptchaAudience.ROLES
                    : SettingConfigGetter.CaptchaConfig.CaptchaAudience.ANONYMOUS)
            .setRoles(java.util.Set.of("authenticated"));
        when(settings.getSecurityConfig()).thenReturn(Mono.just(
            new SettingConfigGetter.SecurityConfig().setCaptcha(captcha)));
        when(contexts.load(any())).thenReturn(loggedIn
            ? Mono.just(new SecurityContextImpl(
                UsernamePasswordAuthenticationToken.authenticated("reader", "", java.util.List.of(
                    new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_authenticated")))))
            : Mono.empty());
        when(manager.generate(any(), any())).thenReturn(Mono.just(
            new CaptchaManager.Captcha("id", "code", "data:image/png;base64,test")));
    }

    @ParameterizedTest
    @CsvSource({
        "false,false,false,false", "false,false,true,false",
        "true,false,false,true", "true,false,true,false",
        "false,true,false,false", "false,true,true,true",
        "true,true,false,true", "true,true,true,true"
    })
    void enforcesConfiguredAudienceForCommentsAndReplies(boolean anonymous, boolean authenticated,
                                                         boolean loggedIn, boolean required) {
        configure(anonymous, authenticated, loggedIn);
        for (var path : java.util.List.of("/apis/api.halo.run/v1alpha1/comments",
            "/apis/api.halo.run/v1alpha1/comments/parent/reply")) {
            var exchange = MockServerWebExchange.from(MockServerHttpRequest.post(path));
            var calls = new AtomicInteger();
            filter.filter(exchange, e -> {
                calls.incrementAndGet();
                return Mono.empty();
            }).block();
            assertThat(calls.get()).isEqualTo(required ? 0 : 1);
            if (required) {
                assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
                assertThat(exchange.getResponse().getHeaders().getFirst("X-Require-Captcha"))
                    .isEqualTo("true");
                assertThat(exchange.getResponse().getBodyAsString().block())
                    .contains(CommentCaptchaFilter.CAPTCHA_REQUIRED_TYPE);
            }
        }
    }

    @ParameterizedTest
    @CsvSource({"true", "false"})
    void verifiesAuthenticatedSubmissionBeforeCallingDownstream(boolean valid) {
        configure(false, true, true);
        when(manager.verify("id", "answer", true)).thenReturn(Mono.just(valid));
        var exchange = MockServerWebExchange.from(MockServerHttpRequest
            .post("/apis/api.halo.run/v1alpha1/comments")
            .header("X-Captcha-Code", "answer")
            .cookie(new HttpCookie(CaptchaCookieResolverImpl.CAPTCHA_COOKIE_KEY, "id")));
        var calls = new AtomicInteger();
        filter.filter(exchange, e -> {
            calls.incrementAndGet();
            return Mono.empty();
        }).block();
        assertThat(calls.get()).isEqualTo(valid ? 1 : 0);
        verify(manager).verify("id", "answer", true);
        if (valid) {
            assertThat(exchange.getResponse().getCookies()
                .getFirst(CaptchaCookieResolverImpl.CAPTCHA_COOKIE_KEY).getMaxAge()).isZero();
        } else {
            assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(exchange.getResponse().getBodyAsString().block())
                .contains(CommentCaptchaFilter.CAPTCHA_INVALID_TYPE);
        }
    }

    @ParameterizedTest
    @CsvSource({"editor,true", "author,true", "reader,false"})
    void matchesAnySelectedRoleForBothSubmissionRoutes(String role, boolean required) {
        configure(false, true, true);
        var config = new SettingConfigGetter.CaptchaConfig().setEnable(true)
            .setAudience(SettingConfigGetter.CaptchaConfig.CaptchaAudience.ROLES)
            .setRoles(java.util.Set.of("editor", "author"));
        when(settings.getSecurityConfig()).thenReturn(Mono.just(
            new SettingConfigGetter.SecurityConfig().setCaptcha(config)));
        when(contexts.load(any())).thenReturn(Mono.just(new SecurityContextImpl(
            UsernamePasswordAuthenticationToken.authenticated("reader", "", java.util.List.of(
                new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_" + role))))));
        for (var path : java.util.List.of("/apis/api.halo.run/v1alpha1/comments",
            "/apis/api.halo.run/v1alpha1/comments/parent/reply")) {
            var exchange = MockServerWebExchange.from(MockServerHttpRequest.post(path));
            var calls = new AtomicInteger();
            filter.filter(exchange, e -> Mono.fromRunnable(calls::incrementAndGet)).block();
            assertThat(calls.get()).isEqualTo(required ? 0 : 1);
            config.setEnable(false);
            filter.filter(exchange, e -> Mono.fromRunnable(calls::incrementAndGet)).block();
            assertThat(calls.get()).isEqualTo(required ? 1 : 2);
            config.setEnable(true);
        }
    }

    @ParameterizedTest
    @CsvSource({"false,false,false", "true,false,true", "false,true,false", "true,true,false"})
    void includesAnonymousIndependentlyOfLoggedInRoles(boolean includeAnonymous,
                                                      boolean loggedIn, boolean required) {
        configure(false, true, loggedIn);
        var config = new SettingConfigGetter.CaptchaConfig().setEnable(true)
            .setAudience(SettingConfigGetter.CaptchaConfig.CaptchaAudience.ROLES)
            .setRoles(java.util.Set.of("editor"))
            .setIncludeAnonymous(includeAnonymous);
        when(settings.getSecurityConfig()).thenReturn(Mono.just(
            new SettingConfigGetter.SecurityConfig().setCaptcha(config)));
        for (var path : java.util.List.of("/apis/api.halo.run/v1alpha1/comments",
            "/apis/api.halo.run/v1alpha1/comments/parent/reply")) {
            var exchange = MockServerWebExchange.from(MockServerHttpRequest.post(path));
            var calls = new AtomicInteger();
            filter.filter(exchange, e -> Mono.fromRunnable(calls::incrementAndGet)).block();
            assertThat(calls.get()).isEqualTo(required ? 0 : 1);
            config.setEnable(false);
            filter.filter(exchange, e -> Mono.fromRunnable(calls::incrementAndGet)).block();
            assertThat(calls.get()).isEqualTo(required ? 1 : 2);
            config.setEnable(true);
        }
    }

    @Test
    void usesAuthenticatedRequestContextWithoutASession() {
        configure(false, true, false);
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.post(
            "/apis/api.halo.run/v1alpha1/comments"));
        var authentication = UsernamePasswordAuthenticationToken.authenticated("reader", "",
            java.util.List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority(
                "ROLE_authenticated")));
        var calls = new AtomicInteger();
        filter.filter(exchange, e -> Mono.fromRunnable(calls::incrementAndGet))
            .contextWrite(org.springframework.security.core.context.ReactiveSecurityContextHolder
                .withAuthentication(authentication))
            .block();
        assertThat(calls.get()).isZero();
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        verifyNoInteractions(contexts);
    }

    @Test
    void unrelatedRequestsPassThroughExactlyOnce() {
        var calls = new AtomicInteger();
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.get(
            "/apis/api.halo.run/v1alpha1/comments"));
        filter.filter(exchange, e -> {
            calls.incrementAndGet();
            return Mono.empty();
        }).block();
        assertThat(calls.get()).isEqualTo(1);
        verifyNoInteractions(settings, manager, contexts);
    }
}
