package com.noahburnette.ignition.apachecamel.camel;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class CamelHttpPublicPathsTest {
    @Test
    void acceptsOnlyExactUnencodedPaths() {
        assertTrue(CamelHttpPublicPaths.validPublicPath("/v1/orders/submit"));
        assertTrue(CamelHttpPublicPaths.validPublicPath("/orders"));
        for (String path : new String[]{"", "/", "orders", "//orders", "/orders/", "/orders/../admin",
            "/orders/./submit", "/orders%2fsubmit", "/orders?x=y", "/orders#fragment", "/orders;param=x",
            "/orders/*", "/orders\\submit", "/orders/ submit", "https://host/orders"}) {
            assertFalse(CamelHttpPublicPaths.validPublicPath(path), path);
        }
        assertFalse(CamelHttpPublicPaths.validPublicPath(null));
    }

    @Test
    void registryRejectsDuplicatesAndCannotReleaseAnotherConsumersClaim() {
        var paths = new CamelHttpPathRegistry();
        var first = new Object();
        var second = new Object();
        paths.register("orders", "/v1/orders/submit", first);
        assertThrows(CamelHttpPathRegistry.DuplicatePathException.class,
            () -> paths.register("other", "/v1/orders/submit", second));
        assertEquals("orders", paths.owner("/v1/orders/submit"));
        paths.unregister("/v1/orders/submit", second);
        assertEquals("orders", paths.owner("/v1/orders/submit"));
        paths.unregister("/v1/orders/submit", first);
        assertNull(paths.owner("/v1/orders/submit"));
        paths.register("replacement", "/v1/orders/submit", second);
        paths.unregister("/v1/orders/submit", first);
        assertEquals("replacement", paths.owner("/v1/orders/submit"));
    }
}
