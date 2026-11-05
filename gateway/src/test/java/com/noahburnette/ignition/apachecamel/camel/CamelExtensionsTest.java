package com.noahburnette.ignition.apachecamel.camel;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CamelExtensionsTest {
    @TempDir Path temporary;
    private static final ClassLoader PARENT = new ClassLoader(CamelExtensionsTest.class.getClassLoader()) {
        @Override public java.net.URL getResource(String name) { return name.equals("camel-extensions/included.properties") ? null : super.getResource(name); }
        @Override public java.io.InputStream getResourceAsStream(String name) { return name.equals("camel-extensions/included.properties") ? null : super.getResourceAsStream(name); }
    };

    @Test void includedJarsAreSeparateRemovableAndStayRemovedAcrossRestarts() throws Exception {
        ClassLoader distribution = distributionHost();
        assertNull(distribution.getResource("org/apache/camel/component/controlbus/ControlBusComponent.class"));
        String removed;
        int includedCount;
        try (var extensions = new CamelExtensions(temporary, distribution)) {
            assertNull(extensions.startupError());
            includedCount = extensions.inventory().size();
            assertTrue(includedCount >= 16);
            assertTrue(extensions.inventory().stream().allMatch(file -> file.get("origin").equals("INCLUDED")));
            removed = (String) extensions.inventory().stream().filter(file -> file.get("name").toString().startsWith("camel-controlbus-")).findFirst().orElseThrow().get("id");
            assertEquals("Control Bus", extensions.schema("controlbus").getAsJsonObject("component").get("title").getAsString());
            extensions.remove(removed);
        }
        for (int restart = 0; restart < 2; restart++) try (var extensions = new CamelExtensions(temporary, distribution)) {
            assertNull(extensions.startupError());
            assertEquals(includedCount - 1, extensions.inventory().size());
            assertThrows(IllegalArgumentException.class, () -> extensions.schema("controlbus"));
            assertNotNull(extensions.schema("cron"));
            assertFalse(Files.exists(temporary.resolve(removed)));
        }
        // A later distribution can carry a new filename/version without undoing the decision.
        var upgraded = new ClassLoader(distribution) {
            @Override public java.io.InputStream getResourceAsStream(String name) {
                return name.equals("camel-extensions/included.properties")
                    ? new ByteArrayInputStream(bytes("camel-controlbus=camel-controlbus-next.jar\ncamel-cron=camel-cron-next.jar\n")) : super.getResourceAsStream(name);
            }
        };
        try (var extensions = new CamelExtensions(temporary, upgraded)) {
            assertNull(extensions.startupError());
            assertEquals(includedCount - 1, extensions.inventory().size());
            assertThrows(IllegalArgumentException.class, () -> extensions.schema("controlbus"));
        }
    }

    private static ClassLoader distributionHost() throws Exception {
        ClassLoader original = CamelExtensionsTest.class.getClassLoader();
        var index = new java.util.Properties();
        try (var input = original.getResourceAsStream("camel-extensions/included.properties")) { index.load(input); }
        var privateResources = new java.util.HashSet<String>();
        for (String name : index.stringPropertyNames()) {
            try (var input = new java.util.jar.JarInputStream(original.getResourceAsStream("camel-extensions/" + index.getProperty(name) + ".bin"))) {
                java.util.jar.JarEntry entry;
                while ((entry = input.getNextJarEntry()) != null) if (!entry.isDirectory()) privateResources.add(entry.getName());
            }
        }
        return new ClassLoader(original) {
            @Override public java.net.URL getResource(String name) { return privateResources.contains(name) ? null : super.getResource(name); }
            @Override public java.io.InputStream getResourceAsStream(String name) { return privateResources.contains(name) ? null : super.getResourceAsStream(name); }
            @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (privateResources.contains(name.replace('.', '/') + ".class")) throw new ClassNotFoundException(name);
                return super.loadClass(name, resolve);
            }
        };
    }

    @Test void stagesLibrariesUntilRestartAndRemovesOnlyAtNextStartup() throws Exception {
        String id;
        try (var extensions = new CamelExtensions(temporary, PARENT)) {
            assertFalse(extensions.schemas().isEmpty());
            id = extensions.upload("dependency.jar", new ByteArrayInputStream(jar(Map.of("example.txt", bytes("hello")))));
            assertEquals("RESTART_REQUIRED", extensions.inventory().get(0).get("state"));
            assertNull(extensions.loader().getResource("example.txt"));
        }
        try (var extensions = new CamelExtensions(temporary, PARENT)) {
            assertEquals("ACTIVE", extensions.inventory().get(0).get("state"));
            assertNotNull(extensions.loader().getResource("example.txt"));
            extensions.remove(id);
            assertEquals("REMOVAL_PENDING", extensions.inventory().get(0).get("state"));
            assertNotNull(extensions.loader().getResource("example.txt"));
        }
        try (var extensions = new CamelExtensions(temporary, PARENT)) {
            assertTrue(extensions.inventory().isEmpty());
            assertNull(extensions.loader().getResource("example.txt"));
        }
    }

    @Test void replacementRemainsStagedUntilRestartAndHasNoHiddenOlderCopy() throws Exception {
        Path libraries = temporary.resolve("replace");
        try (var extensions = new CamelExtensions(libraries, PARENT)) {
            extensions.upload("library-v1.jar", new ByteArrayInputStream(jar(Map.of("version.txt", bytes("one")))));
        }
        try (var extensions = new CamelExtensions(libraries, PARENT)) {
            String old = extensions.inventory().get(0).get("id").toString();
            extensions.remove(old);
            extensions.upload("library-v2.jar", new ByteArrayInputStream(jar(Map.of("version.txt", bytes("two")))));
            try (var input = extensions.loader().getResourceAsStream("version.txt")) { assertEquals("one", new String(input.readAllBytes(), StandardCharsets.UTF_8)); }
        }
        try (var extensions = new CamelExtensions(libraries, PARENT)) {
            assertEquals(1, extensions.inventory().size());
            assertEquals("library-v2.jar", extensions.inventory().get(0).get("name"));
            try (var input = extensions.loader().getResourceAsStream("version.txt")) { assertEquals("two", new String(input.readAllBytes(), StandardCharsets.UTF_8)); }
        }
    }

    @Test void bulkRemovalValidatesEverythingBeforePublishingOneAtomicPlan() throws Exception {
        try (var extensions = new CamelExtensions(temporary, PARENT)) {
            String first = extensions.upload("first.jar", new ByteArrayInputStream(jar(Map.of("first.txt", bytes("first")))));
            String second = extensions.upload("second.jar", new ByteArrayInputStream(jar(Map.of("second.txt", bytes("second")))));
            assertThrows(IllegalArgumentException.class, () -> extensions.removeAll(List.of(first, "unknown")));
            assertTrue(extensions.inventory().stream().noneMatch(file -> file.get("state").equals("REMOVAL_PENDING")));
            extensions.removeAll(List.of(first, second));
            assertTrue(extensions.inventory().stream().allMatch(file -> file.get("state").equals("REMOVAL_PENDING")));
            try (var files = Files.list(temporary)) { assertEquals(1, files.filter(path -> path.getFileName().toString().endsWith(".plan")).count()); }
        }
        try (var extensions = new CamelExtensions(temporary, PARENT)) { assertTrue(extensions.inventory().isEmpty()); }
    }

    @Test void rejectsTraversalCorruptionBundledClassesAndWrongCamelVersion() throws Exception {
        try (var extensions = new CamelExtensions(temporary, PARENT)) {
            assertThrows(IllegalArgumentException.class, () -> extensions.upload("../outside.jar", new ByteArrayInputStream(new byte[0])));
            assertThrows(Exception.class, () -> extensions.upload("broken.jar", new ByteArrayInputStream(bytes("not a jar"))));
            byte[] clazz;
            try (var input = PARENT.getResourceAsStream("org/apache/camel/CamelContext.class")) { clazz = input.readAllBytes(); }
            byte[] bundled = jar(Map.of("org/apache/camel/CamelContext.class", clazz));
            assertThrows(IllegalArgumentException.class, () -> extensions.upload("core.jar", new ByteArrayInputStream(bundled)));
            byte[] wrongVersion = jar(Map.of("META-INF/maven/org.apache.camel/camel-example/pom.properties", bytes("version=4.21.0\n")));
            assertThrows(IllegalArgumentException.class, () -> extensions.upload("wrong.jar", new ByteArrayInputStream(wrongVersion)));
            assertTrue(extensions.inventory().isEmpty());
            try (var files = Files.list(temporary)) { assertEquals(0, files.count()); }
        }
    }

    @Test void rejectsDuplicateClassesAndMetadataWithoutExecutingCode() throws Exception {
        byte[] clazz = {(byte) 0xca, (byte) 0xfe, (byte) 0xba, (byte) 0xbe, 0, 0, 0, 61};
        try (var extensions = new CamelExtensions(temporary, PARENT)) {
            byte[] first = jar(Map.of("thirdparty/Example.class", clazz));
            extensions.upload("first.jar", new ByteArrayInputStream(first));
            assertThrows(IllegalArgumentException.class, () -> extensions.upload("second.jar", new ByteArrayInputStream(first)));
            byte[] reserved = jar(Map.of("META-INF/org/apache/camel/component/platform-http/platform-http.json", bytes("{\"component\":{\"scheme\":\"platform-http\"}}")));
            assertThrows(IllegalArgumentException.class, () -> extensions.upload("reserved.jar", new ByteArrayInputStream(reserved)));
        }
    }

    @Test void enforcesStreamingUploadLimitAndCleansTemporaryFile() throws Exception {
        try (var extensions = new CamelExtensions(temporary, PARENT)) {
            var input = new java.io.InputStream() {
                public int read() { return 0; }
                public int read(byte[] buffer, int offset, int length) { java.util.Arrays.fill(buffer, offset, offset + length, (byte) 0); return length; }
            };
            assertThrows(IllegalArgumentException.class, () -> extensions.upload("large.jar", input));
            assertTrue(extensions.inventory().isEmpty());
        }
    }

    @Test void checksumFailureKeepsBuiltinsAndRepairInventoryAvailable() throws Exception {
        String id;
        try (var extensions = new CamelExtensions(temporary, PARENT)) {
            id = extensions.upload("modified.jar", new ByteArrayInputStream(jar(Map.of("sample.txt", bytes("original")))));
        }
        Files.writeString(temporary.resolve(id), "altered on disk");
        try (var extensions = new CamelExtensions(temporary, PARENT)) {
            assertTrue(extensions.startupError().contains("checksum"));
            assertFalse(extensions.schemas().isEmpty());
            assertEquals("BLOCKED", extensions.inventory().get(0).get("state"));
            extensions.remove(id);
        }
        try (var extensions = new CamelExtensions(temporary, PARENT)) {
            assertNull(extensions.startupError());
            assertTrue(extensions.inventory().isEmpty());
        }
    }

    @Test void bundledOptionalProviderCanSeeAnUploadedApiWithoutDuplicatingCoreClasses() throws Exception {
        Path api = temporary.resolve("OptionalApi.java");
        Path provider = temporary.resolve("BundledProvider.java");
        Path compiled = temporary.resolve("compiled"); Files.createDirectories(compiled);
        Files.writeString(api, "package extension; public interface OptionalApi { String value(); }");
        Files.writeString(provider, "package org.apache.camel.component.optionaltest; public class BundledProvider implements extension.OptionalApi { public String value() { return \"optional-provider\"; } }");
        assertEquals(0, javax.tools.ToolProvider.getSystemJavaCompiler().run(null, null, null, "-d", compiled.toString(), api.toString(), provider.toString()));
        Path parentJar = temporary.resolve("parent.jar");
        Files.write(parentJar, jar(Map.of("org/apache/camel/component/optionaltest/BundledProvider.class", Files.readAllBytes(compiled.resolve("org/apache/camel/component/optionaltest/BundledProvider.class")))));
        byte[] apiJar = jar(Map.of("extension/OptionalApi.class", Files.readAllBytes(compiled.resolve("extension/OptionalApi.class"))));
        Path libraries = temporary.resolve("extensions");
        try (var parent = new java.net.URLClassLoader(new java.net.URL[]{parentJar.toUri().toURL()}, PARENT)) {
            try (var extensions = new CamelExtensions(libraries, parent)) { extensions.upload("optional-api.jar", new ByteArrayInputStream(apiJar)); }
            try (var extensions = new CamelExtensions(libraries, parent)) {
                var type = extensions.loader().loadClass("org.apache.camel.component.optionaltest.BundledProvider");
                assertSame(extensions.loader(), type.getClassLoader());
                assertEquals("optional-provider", type.getMethod("value").invoke(type.getConstructor().newInstance()));
                assertSame(org.apache.camel.CamelContext.class, extensions.loader().loadClass("org.apache.camel.CamelContext"));
                assertSame(org.apache.camel.component.quartz.QuartzComponent.class, extensions.loader().loadClass("org.apache.camel.component.quartz.QuartzComponent"));
            }
        }
    }

    @Test void loadsARealUploadedComponentWithNativeCamelDiscovery() throws Exception {
        Path source = temporary.resolve("AddonComponent.java");
        Files.writeString(source, """
            package extension;
            public class AddonComponent extends org.apache.camel.component.direct.DirectComponent {
                private String greeting;
                public void setGreeting(String value) { greeting = value; }
                public String getGreeting() { return greeting; }
            }
            """);
        String classpath = java.util.stream.Stream.of(org.apache.camel.component.direct.DirectComponent.class,
            org.apache.camel.support.DefaultComponent.class, org.apache.camel.CamelContext.class,
            org.apache.camel.util.ObjectHelper.class).map(type -> {
                try { return Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toString(); }
                catch (Exception error) { throw new IllegalStateException(error); }
            }).distinct().collect(java.util.stream.Collectors.joining(java.io.File.pathSeparator));
        assertEquals(0, javax.tools.ToolProvider.getSystemJavaCompiler().run(null, null, null, "-classpath", classpath, "-d", temporary.toString(), source.toString()));
        byte[] binary = Files.readAllBytes(temporary.resolve("extension/AddonComponent.class"));
        byte[] addon = jar(Map.of("extension/AddonComponent.class", binary,
            "META-INF/services/org/apache/camel/component/addon", bytes("class=extension.AddonComponent\n"),
            "META-INF/org/apache/camel/component/addon/addon.json", bytes("""
                {"component":{"scheme":"addon","title":"Addon","javaType":"extension.AddonComponent"},
                "componentProperties":{"greeting":{"type":"string","required":true}},"properties":{}}
                """)));
        Path libraries = temporary.resolve("libraries");
        try (var extensions = new CamelExtensions(libraries, PARENT)) {
            extensions.upload("addon.jar", new ByteArrayInputStream(addon));
            assertThrows(IllegalArgumentException.class, () -> extensions.schema("addon"));
        }
        try (var extensions = new CamelExtensions(libraries, PARENT);
             var runtime = new CamelRouteRuntime("extension", new CamelRouteResource("""
                 - route:
                     from:
                       uri: addon:start
                       steps:
                         - setBody:
                             constant: extension-loaded
                 """), context -> {
                 var component = context.getComponent("addon");
                 assertSame(extensions.loader(), component.getClass().getClassLoader());
                 org.apache.camel.support.PropertyBindingSupport.bindProperties(context, component, new java.util.HashMap<>(Map.of("greeting", "hello")));
                 try { assertEquals("hello", component.getClass().getMethod("getGreeting").invoke(component)); }
                 catch (Exception error) { throw new IllegalStateException(error); }
             }, null, () -> {}, extensions.loader())) {
            assertEquals("Addon", extensions.schema("addon").getAsJsonObject("component").get("title").getAsString());
            assertEquals("extension-loaded", runtime.request("addon:start", "input"));
        }
    }

    private static byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }
    private static byte[] jar(Map<String, byte[]> entries) throws Exception {
        var output = new ByteArrayOutputStream();
        try (var jar = new JarOutputStream(output)) {
            for (var entry : entries.entrySet()) { jar.putNextEntry(new JarEntry(entry.getKey())); jar.write(entry.getValue()); jar.closeEntry(); }
        }
        return output.toByteArray();
    }
}
