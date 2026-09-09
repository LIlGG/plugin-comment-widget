package run.halo.comment.widget;

import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import org.springframework.stereotype.Component;
import run.halo.app.extension.GroupVersionKind;
import run.halo.app.extension.SchemeManager;
import run.halo.app.extension.index.IndexSpecs;
import run.halo.app.plugin.BasePlugin;
import run.halo.app.plugin.PluginContext;
import run.halo.comment.widget.upload.CommentSubmission;
import run.halo.comment.widget.upload.CommentUpload;
import run.halo.comment.widget.upload.UploadReferences;

/**
 * @author ryanwang
 * @since 2.0.0
 */
@Component
public class CommentWidgetPlugin extends BasePlugin {

    private final SchemeManager schemeManager;
    private final RateLimiterRegistry rateLimiterRegistry;
    private final RateLimiterKeyRegistry rateLimiterKeyRegistry;

    public CommentWidgetPlugin(
        PluginContext pluginContext,
        RateLimiterRegistry rateLimiterRegistry,
        RateLimiterKeyRegistry rateLimiterKeyRegistry,
        SchemeManager schemeManager
    ) {
        super(pluginContext);
        this.schemeManager = schemeManager;
        this.rateLimiterRegistry = rateLimiterRegistry;
        this.rateLimiterKeyRegistry = rateLimiterKeyRegistry;
    }

    @Override
    public void start() {
        // A hot reload may leave a scheme from an older plugin class loader.
        schemeManager
            .fetch(new GroupVersionKind("commentwidget.halo.run", "v1alpha1", "CommentUpload"))
            .filter(scheme -> scheme.type() != CommentUpload.class)
            .ifPresent(schemeManager::unregister);
        schemeManager
            .fetch(new GroupVersionKind("commentwidget.halo.run", "v1alpha1", "CommentSubmission"))
            .filter(scheme -> scheme.type() != CommentSubmission.class)
            .ifPresent(schemeManager::unregister);
        schemeManager.register(CommentUpload.class, specs -> {
            specs.add(
                IndexSpecs.<CommentUpload, String>single("credentialHash", String.class).indexFunc(
                    u -> u.getSpec().getCredentialHash()
                )
            );
            specs.add(
                IndexSpecs.<CommentUpload, String>single("uploadUrl", String.class).indexFunc(u ->
                    UploadReferences.canonical(u.getSpec().getUrl())
                )
            );
        });
        schemeManager.register(CommentSubmission.class, specs ->
            specs.add(
                IndexSpecs.<CommentSubmission, String>single(
                    "credentialHash",
                    String.class
                ).indexFunc(s -> s.getSpec().getCredentialHash())
            )
        );
    }

    @Override
    public void stop() {
        rateLimiterKeyRegistry.getAllKeys().forEach(rateLimiterRegistry::remove);
        rateLimiterKeyRegistry.clear();
        schemeManager
            .fetch(new GroupVersionKind("commentwidget.halo.run", "v1alpha1", "CommentUpload"))
            .ifPresent(schemeManager::unregister);
        schemeManager
            .fetch(new GroupVersionKind("commentwidget.halo.run", "v1alpha1", "CommentSubmission"))
            .ifPresent(schemeManager::unregister);
    }
}
