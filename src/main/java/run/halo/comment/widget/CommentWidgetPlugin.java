package run.halo.comment.widget;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;
import run.halo.app.extension.ConfigMap;
import run.halo.app.extension.ExtensionClient;
import run.halo.app.plugin.BasePlugin;
import run.halo.app.plugin.PluginContext;

/**
 * @author ryanwang
 * @since 2.0.0
 */
@Component
public class CommentWidgetPlugin extends BasePlugin {
    private final PluginContext context;
    private final ExtensionClient client;

    public CommentWidgetPlugin(PluginContext pluginContext, ExtensionClient client) {
        super(pluginContext);
        this.context = pluginContext;
        this.client = client;
    }

    @Override
    public void start() {
        client.fetch(ConfigMap.class, context.getConfigMapName()).ifPresent(config -> {
            if (config.getData() == null || !config.getData().containsKey("security")) {
                return;
            }
            var security = config.getData().get("security");
            var migrated = migrateCaptchaSettings(security);
            if (!security.equals(migrated)) {
                config.getData().put("security", migrated);
                client.update(config);
            }
        });
    }

    static String migrateCaptchaSettings(String security) {
        try {
            var mapper = new ObjectMapper();
            var root = mapper.readTree(security);
            if (!(root.path("captcha") instanceof ObjectNode captcha) || captcha.has("audience")) {
                return security;
            }
            var anonymous = captcha.path("anonymousCommentCaptcha").asBoolean(false);
            var authenticated = captcha.path("authenticatedCommentCaptcha").asBoolean(false);
            captcha.put("enable", anonymous || authenticated);
            var audience = "ANONYMOUS";
            if (anonymous && authenticated) {
                audience = "ALL";
            } else if (authenticated) {
                audience = "ROLES";
            }
            captcha.put("audience", audience);
            var roles = captcha.putArray("roles");
            if (authenticated && !anonymous) {
                roles.add("authenticated");
            }
            captcha.remove("anonymousCommentCaptcha");
            captcha.remove("authenticatedCommentCaptcha");
            return mapper.writeValueAsString(root);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("Cannot migrate captcha settings", e);
        }
    }
}
