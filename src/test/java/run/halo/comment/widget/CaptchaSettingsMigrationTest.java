package run.halo.comment.widget;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class CaptchaSettingsMigrationTest {
    @ParameterizedTest
    @CsvSource({"false,false,false,ANONYMOUS", "true,false,true,ANONYMOUS",
        "false,true,true,ROLES", "true,true,true,ALL"})
    void migratesLegacyFlags(boolean anonymous, boolean authenticated, boolean enabled,
                            String audience) throws Exception {
        var original = "{\"captcha\":{\"enable\":false,\"anonymousCommentCaptcha\":" + anonymous
            + ",\"authenticatedCommentCaptcha\":" + authenticated
            + ",\"type\":\"ARITHMETIC\",\"arithmeticRange\":50}}";
        var migrated = CommentWidgetPlugin.migrateCaptchaSettings(original);
        var captcha = new ObjectMapper().readTree(migrated).path("captcha");
        assertThat(captcha.path("enable").asBoolean()).isEqualTo(enabled);
        assertThat(captcha.path("audience").asText()).isEqualTo(audience);
        assertThat(captcha.path("type").asText()).isEqualTo("ARITHMETIC");
        assertThat(captcha.path("arithmeticRange").asInt()).isEqualTo(50);
        assertThat(captcha.has("anonymousCommentCaptcha")).isFalse();
        assertThat(captcha.path("roles").size()).isEqualTo(authenticated && !anonymous ? 1 : 0);
        assertThat(CommentWidgetPlugin.migrateCaptchaSettings(migrated)).isEqualTo(migrated);
    }

    @Test
    void preservesNewSettingsAndAbsentCaptcha() {
        for (var json : java.util.List.of("{}",
            "{\"captcha\":{\"enable\":false,\"audience\":\"ROLES\",\"roles\":[\"editor\"]}}")) {
            assertThat(CommentWidgetPlugin.migrateCaptchaSettings(json)).isEqualTo(json);
        }
    }
}
