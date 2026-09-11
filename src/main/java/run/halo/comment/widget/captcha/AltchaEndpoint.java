package run.halo.comment.widget.captcha;

import com.google.common.util.concurrent.RateLimiter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerResponse;
import run.halo.app.core.extension.endpoint.CustomEndpoint;
import run.halo.app.extension.GroupVersion;
import run.halo.comment.widget.SettingConfigGetter;

@Component
@RequiredArgsConstructor
public class AltchaEndpoint implements CustomEndpoint {
    private final AltchaService service;
    private final SettingConfigGetter settings;
    private final RateLimiter limiter = RateLimiter.create(10);

    @Override
    public RouterFunction<ServerResponse> endpoint() {
        return RouterFunctions.route().GET("captcha/-/altcha", request ->
            settings.getSecurityConfig().flatMap(config -> {
                var captcha = config.getCaptcha();
                if (!captcha.isEnable() || captcha.getType() != CaptchaType.ALTCHA) {
                    return ServerResponse.notFound().build();
                }
                if (!limiter.tryAcquire()) {
                    return ServerResponse.status(HttpStatus.TOO_MANY_REQUESTS)
                        .header("Retry-After", "1").cacheControl(CacheControl.noStore()).build();
                }
                return service.createChallenge().flatMap(challenge -> ServerResponse.ok()
                    .cacheControl(CacheControl.noStore())
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(challenge.toJson()));
            })).build();
    }

    @Override
    public GroupVersion groupVersion() {
        return GroupVersion.parseAPIVersion("api.commentwidget.halo.run/v1alpha1");
    }
}
