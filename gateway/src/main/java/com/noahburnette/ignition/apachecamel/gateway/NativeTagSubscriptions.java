package com.noahburnette.ignition.apachecamel.gateway;

import com.inductiveautomation.ignition.common.TagPathUtils;
import com.inductiveautomation.ignition.common.tags.model.TagManager;
import com.inductiveautomation.ignition.common.tags.model.TagPath;
import com.inductiveautomation.ignition.common.tags.model.event.TagChangeListener;
import com.inductiveautomation.ignition.common.tags.paths.GlobPattern;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Lifecycle for native subscriptions, including the 8.3.8 cold-prefix workaround. */
final class NativeTagSubscriptions {
    private NativeTagSubscriptions() { }

    static AutoCloseable subscribe(TagManager manager, TagPath path, TagChangeListener listener) throws Exception {
        TagPath prefix = TagPathUtils.isGlobPath(path) ? GlobPattern.parse(path).getStaticPrefix(path) : null;
        if (prefix == null || prefix.getPathLength() == 0) {
            return attach(manager, path, listener);
        }
        // 8.3.8's native glob installer only walks an existing subscription-tree
        // prefix. A temporary folder subscription materializes that prefix; the
        // native glob still owns all discovery and future tag registrations.
        AutoCloseable subscription = null;
        try (var anchor = attach(manager, prefix, ignored -> { })) {
            subscription = attach(manager, path, listener);
        } catch (Exception error) {
            if (subscription != null) {
                try { subscription.close(); } catch (Exception cleanup) { error.addSuppressed(cleanup); }
            }
            throw error;
        }
        return subscription;
    }

    private static AutoCloseable attach(TagManager manager, TagPath path, TagChangeListener listener) throws Exception {
        try {
            manager.subscribeAsync(path, listener).get(30, TimeUnit.SECONDS);
        } catch (Exception error) {
            manager.unsubscribeAsync(path, listener);
            if (error instanceof InterruptedException) { Thread.currentThread().interrupt(); }
            throw error;
        }
        var closed = new AtomicBoolean();
        return () -> {
            if (!closed.compareAndSet(false, true)) { return; }
            try {
                manager.unsubscribeAsync(path, listener).get(5, TimeUnit.SECONDS);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw error;
            }
        };
    }
}
