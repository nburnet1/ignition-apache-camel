package com.noahburnette.ignition.apachecamel;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class TagPathsTest {
    @Test void subscriptionsAllowNativeGlobSyntaxButWritesStayExact() {
        for (String path : new String[]{"[default]Machines/*/Complete", "[default]Machines/**/Complete",
            "[default]Machines/press-?/Complete", "[default]Machines/press-*/Complete"}) {
            assertEquals(path, TagPaths.requireSubscribable(path).toStringFull());
            assertThrows(IllegalArgumentException.class, () -> TagPaths.requireWritable(path));
        }
        assertEquals("[default]Exact", TagPaths.requireWritable("[default]Exact").toStringFull());
    }

    @Test void subscriptionsStillRequireAnExactProviderAndNoTagProperties() {
        for (String path : new String[]{"Machines/*/Complete", "[*]Machines/*/Complete",
            "[def?ult]Machines/*/Complete", "[default]Machines/*/Complete.Quality", "[default]", ""}) {
            assertThrows(IllegalArgumentException.class, () -> TagPaths.requireSubscribable(path), path);
        }
    }
}
