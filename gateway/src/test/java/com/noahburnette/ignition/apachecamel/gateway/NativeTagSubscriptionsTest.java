package com.noahburnette.ignition.apachecamel.gateway;

import static org.junit.jupiter.api.Assertions.*;

import com.inductiveautomation.ignition.common.tags.model.TagManager;
import com.inductiveautomation.ignition.common.tags.model.TagPath;
import com.inductiveautomation.ignition.common.tags.model.event.TagChangeListener;
import com.noahburnette.ignition.apachecamel.TagPaths;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

class NativeTagSubscriptionsTest {
    @Test void materializesColdPrefixBeforeAttachingGlobThenReleasesAnchor() throws Exception {
        var calls = new ArrayList<String>();
        var manager = manager(calls, false);
        var glob = TagPaths.requireSubscribable("[default]Machines/*/Complete");
        var subscription = NativeTagSubscriptions.subscribe(manager, glob, ignored -> { });
        assertEquals(List.of("subscribeAsync:[default]Machines", "subscribeAsync:[default]Machines/*/Complete",
            "unsubscribeAsync:[default]Machines"), calls);
        subscription.close();
        subscription.close();
        assertEquals(4, calls.size());
        assertEquals("unsubscribeAsync:[default]Machines/*/Complete", calls.get(3));
    }

    @Test void failedGlobAttachmentCleansUpBothSubscriptions() {
        var calls = new ArrayList<String>();
        assertThrows(Exception.class, () -> NativeTagSubscriptions.subscribe(manager(calls, true),
            TagPaths.requireSubscribable("[default]Machines/*/Complete"), ignored -> { }));
        assertEquals(List.of("subscribeAsync:[default]Machines", "subscribeAsync:[default]Machines/*/Complete",
            "unsubscribeAsync:[default]Machines/*/Complete", "unsubscribeAsync:[default]Machines"), calls);
    }

    @Test void exactPathsAndProviderRootGlobsDoNotNeedAnAnchor() throws Exception {
        for (String path : List.of("[default]Input", "[default]**/Complete")) {
            var calls = new ArrayList<String>();
            try (var subscription = NativeTagSubscriptions.subscribe(manager(calls, false),
                TagPaths.requireSubscribable(path), ignored -> { })) {
                assertEquals(List.of("subscribeAsync:" + path), calls);
            }
            assertEquals(List.of("subscribeAsync:" + path, "unsubscribeAsync:" + path), calls);
        }
    }

    private static TagManager manager(List<String> calls, boolean failGlob) {
        return (TagManager) Proxy.newProxyInstance(TagManager.class.getClassLoader(), new Class<?>[]{TagManager.class},
            (proxy, method, args) -> {
                if (args != null && args.length == 2 && args[0] instanceof TagPath path && args[1] instanceof TagChangeListener) {
                    calls.add(method.getName() + ":" + path.toStringFull());
                    if (failGlob && method.getName().equals("subscribeAsync") && path.toStringFull().contains("*")) {
                        return CompletableFuture.failedFuture(new IllegalStateException("glob attach failed"));
                    }
                    return CompletableFuture.completedFuture(null);
                }
                throw new UnsupportedOperationException(method.getName());
            });
    }
}
