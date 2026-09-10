package run.halo.comment.widget.captcha;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import run.halo.app.infra.AnonymousUserConst;
import run.halo.comment.widget.SettingConfigGetter;

/** Shared audience decision for the public configuration and submission validation. */
@Component
public class CaptchaRequirement {
    public Mono<Boolean> isRequired(SettingConfigGetter.CaptchaConfig config) {
        return ReactiveSecurityContextHolder.getContext()
            .map(SecurityContext::getAuthentication)
            .map(authentication -> matches(config, authentication))
            .defaultIfEmpty(matches(config, null));
    }

    private boolean matches(SettingConfigGetter.CaptchaConfig config,
                            Authentication authentication) {
        if (!config.isEnable()) {
            return false;
        }
        var anonymous = authentication == null || !authentication.isAuthenticated()
            || AnonymousUserConst.isAnonymousUser(authentication.getName());
        if (config.getAudience() == SettingConfigGetter.CaptchaConfig.CaptchaAudience.ALL) {
            return true;
        }
        if (config.getAudience() == SettingConfigGetter.CaptchaConfig.CaptchaAudience.ROLES) {
            if (anonymous) {
                return config.isIncludeAnonymous();
            }
            if (config.getRoles() == null || config.getRoles().isEmpty()) {
                return false;
            }
            return authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .filter(authority -> authority.startsWith("ROLE_"))
                .map(authority -> authority.substring("ROLE_".length()))
                .anyMatch(config.getRoles()::contains);
        }
        return anonymous;
    }
}
