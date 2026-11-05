package com.noahburnette.ignition.apachecamel.camel;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.jar.JarFile;

/** Runs with the distributable JAR and logging API, not the unpackaged Camel dependencies. */
public final class PackagedCamelProbe {
    public static void main(String[] args) throws Exception {
        Path bundled = Path.of(CamelRouteRuntime.class.getProtectionDomain()
            .getCodeSource().getLocation().toURI());
        Path directory = java.nio.file.Files.createTempDirectory("packaged-camel-extensions-");
        ClassLoader parent = CamelRouteRuntime.class.getClassLoader();
        ClassLoader previous = Thread.currentThread().getContextClassLoader();
        try {
            try (var extensions = new CamelExtensions(directory, parent)) {
                if (extensions.startupError() != null) throw new AssertionError(extensions.startupError());
                Thread.currentThread().setContextClassLoader(extensions.loader());
                runProbe(bundled, extensions);
                for (String artifact : java.util.List.of("camel-http-", "camel-paho-mqtt5-", "camel-quartz-", "camel-file-")) {
                    var file = extensions.inventory().stream().filter(value -> value.get("name").toString().matches(artifact + "[0-9].*\\.jar")).findFirst().orElseThrow();
                    if (artifact.equals("camel-http-")) {
                        var plan = extensions.graph().preview(file.get("id").toString(), true);
                        if (!plan.cleanupSafe() || plan.removeIds().size() < 2) throw new AssertionError("Verified distribution must support exclusive dependency cleanup");
                        extensions.removeAll(plan.removeIds());
                    } else extensions.remove(file.get("id").toString());
                }
            }
            try (var removed = new CamelExtensions(directory, parent)) {
                Thread.currentThread().setContextClassLoader(removed.loader());
                if (removed.startupError() != null) throw new AssertionError(removed.startupError());
                for (String scheme : java.util.List.of("http", "https", "paho-mqtt5", "quartz", "file")) {
                    try { removed.schema(scheme); throw new AssertionError("Removed component still available: " + scheme); }
                    catch (IllegalArgumentException expected) { }
                }
                for (String type : java.util.List.of("http.HttpComponent", "paho.mqtt5.PahoMqtt5Component", "quartz.QuartzComponent", "file.FileComponent")) {
                    try { removed.loader().loadClass("org.apache.camel.component." + type); throw new AssertionError("Hidden component copy: " + type); }
                    catch (ClassNotFoundException expected) { }
                }
                var engine = new IgnitionPlatformHttpEngine();
                try (var nativeRoute = new CamelRouteRuntime("native-after-removal", new CamelRouteResource("""
                    - route:
                        from:
                          uri: ignition-gateway-http:/native?httpMethodRestrict=POST
                          steps:
                            - setBody:
                                constant: native-still-running
                    """), engine::install, null, () -> {}, removed.loader())) {
                    if (!"native-still-running".equals(engine.request("/native", null, java.util.Map.of()).body())) throw new AssertionError("Native bridge failed after removal");
                }
            }
        } finally {
            Thread.currentThread().setContextClassLoader(previous);
            try (var files = java.nio.file.Files.list(directory)) { for (Path file : files.toList()) java.nio.file.Files.delete(file); }
            java.nio.file.Files.delete(directory);
        }
    }

    private static void runProbe(Path bundled, CamelExtensions extensions) throws Exception {
        // Model validation must work from the distribution without resolving application beans.
        CamelRouteRuntime.validate(new CamelRouteResource("""
            - beans:
                - name: deferred
                  type: example.NotInstalled
            - route:
                from:
                  uri: direct:packaged-validation
                  steps:
                    - to: bean:deferred
            """));
        var tick = new java.util.concurrent.CountDownLatch(1);
        try (var cron = new CamelRouteRuntime("packaged-memory-cron", new CamelRouteResource("""
            - route:
                from:
                  uri: cron:packaged-tick?schedule=0/1+*+*+*+*+?
                  steps:
                    - process:
                        ref: packagedCronTick
            """), context -> {
                org.apache.camel.support.PropertyBindingSupport.bindProperties(context, context.getComponent("cron"),
                    new java.util.HashMap<>(java.util.Map.of("cronService", "quartz")));
                context.getRegistry().bind("packagedCronTick", (org.apache.camel.Processor) exchange -> tick.countDown());
            }, null, () -> {}, extensions.loader())) {
            if (!tick.await(10, java.util.concurrent.TimeUnit.SECONDS)) throw new AssertionError("Packaged in-memory Cron/Quartz scheduling did not fire");
        }
        // Real protocol/management discovery from the distributable, never loose Jolokia/Camel JARs.
        try (var management = new CamelHawtio(true, CamelHawtio.JVM_READONLY_SCOPE);
             var managed = new CamelRouteRuntime("packaged-management", new CamelRouteResource("""
                 - route:
                     id: packaged-managed
                     from:
                       uri: direct:managed
                       steps:
                          - setBody:
                              constant: managed-ok
                          - to: direct:managed-target
                 - route:
                     id: packaged-managed-target
                     from:
                       uri: direct:managed-target
                       steps:
                         - process:
                             ref: managedProcessor
                 """), context -> {
                     context.getRegistry().bind("managedProcessor", (org.apache.camel.Processor) exchange -> {});
                     management.install(context);
                 }, null, () -> {}, extensions.loader())) {
            String search = management.post("{\"type\":\"search\",\"mbean\":\"org.apache.camel:type=routes,*\"}".getBytes(StandardCharsets.UTF_8));
            if (!search.contains("packaged-managed")) throw new AssertionError("Packaged Jolokia/Camel management failed: " + search);
            if (!"managed-ok".equals(managed.request("direct:managed", null))) throw new AssertionError("Managed packaged route failed");
            var parser = new org.jolokia.json.parser.JSONParser();
            var contexts = (org.jolokia.json.JSONObject) parser.parse(management.post(
                "{\"type\":\"search\",\"mbean\":\"org.apache.camel:type=context,*\"}".getBytes(StandardCharsets.UTF_8)));
            String context = ((java.util.List<?>) contexts.get("value")).stream()
                .filter(value -> value.toString().contains("integration-packaged-management")).findFirst().orElseThrow().toString();
            var request = new org.jolokia.json.JSONObject();
            request.putAll(java.util.Map.of("type", "exec", "mbean", context, "operation", "dumpRoutesAsXml()"));
            var xml = (org.jolokia.json.JSONObject) parser.parse(management.post(request.toJSONString().getBytes(StandardCharsets.UTF_8)));
            if (!Long.valueOf(200).equals(xml.get("status")) || !String.valueOf(xml.get("value")).contains("packaged-managed")) {
                throw new AssertionError("Packaged Camel XML inspection failed: " + xml);
            }
            if (management.controlEnabled()) throw new AssertionError("Read-only scope must override the control flag");
        }
        try (var jar = new JarFile(bundled.toFile())) {
            if (jar.getEntry("org/slf4j/LoggerFactory.class") != null) {
                throw new AssertionError("The Gateway must supply the logging facade");
            }
            var included = new java.util.Properties();
            var index = jar.getJarEntry("camel-extensions/included.properties");
            if (index == null) throw new AssertionError("Missing included extension index");
            try (var input = jar.getInputStream(index)) { included.load(input); }
            if (!included.keySet().containsAll(java.util.Set.of("camel-http", "camel-paho-mqtt5", "camel-quartz", "camel-file", "camel-kamelet"))) throw new AssertionError("Every integration must be a managed JAR");
            if (included.containsKey("camel-paho") || included.containsKey("org.eclipse.paho.client.mqttv3")) throw new AssertionError("MQTT 3 libraries must not be included");
            for (String filename : included.stringPropertyNames()) {
                var resource = jar.getJarEntry("camel-extensions/" + included.getProperty(filename) + ".bin");
                if (resource == null) throw new AssertionError("Missing intact starter JAR: " + filename);
                boolean foundClasses = false;
                try (var nested = new java.util.jar.JarInputStream(jar.getInputStream(resource))) {
                    java.util.jar.JarEntry entry;
                    while ((entry = nested.getNextJarEntry()) != null) if (entry.getName().endsWith(".class") && !entry.getName().endsWith("module-info.class")) {
                        foundClasses = true;
                        if (jar.getJarEntry(entry.getName()) != null) throw new AssertionError("Included classes must not also be shaded: " + entry.getName());
                    }
                }
                if (!foundClasses) throw new AssertionError("Empty starter library: " + filename);
            }
        }
        try (var discovery = new org.apache.camel.impl.DefaultCamelContext()) {
            discovery.setApplicationContextClassLoader(extensions.loader());
            discovery.getClassResolver().addClassLoader(extensions.loader());
            if (!CamelComponentCatalog.VERSION.equals(discovery.getVersion())) throw new AssertionError("Catalogue/BOM version mismatch");
            for (var entry : CamelComponentCatalog.entries()) {
                if (entry.artifact().equals("module-native")) continue;
                for (String component : entry.components()) {
                    if (discovery.getComponent(component) == null) throw new AssertionError("Missing bundled component: " + component);
                }
                for (String language : entry.languages()) discovery.resolveLanguage(language);
                for (String format : entry.dataFormats()) {
                    if (discovery.createDataFormat(format) == null) throw new AssertionError("Missing bundled data format: " + format);
                }
            }
            if (!(discovery.getComponent("platform-http") instanceof
                org.apache.camel.component.platform.http.PlatformHttpComponent)) {
                throw new AssertionError("Packaged platform-http discovery failed");
            }
        }
        try (var extras = new CamelRouteRuntime("packaged-essentials", new CamelRouteResource("""
            - route:
                id: json
                from:
                  uri: direct:json
                  steps:
                    - unmarshal:
                        json:
                          library: Jackson
                    - setBody:
                        jsonpath: $.answer
            - route:
                id: queue-ingress
                from:
                  uri: direct:queue
                  steps:
                    - to: seda:work?size=8&waitForTaskToComplete=Always&timeout=2000
            - route:
                id: queue-worker
                from:
                  uri: seda:work?size=8
                  steps:
                    - setBody:
                        constant: packaged-queue-ok
            """), context -> {}, null, () -> {}, extensions.loader())) {
            if (!"42".equals(String.valueOf(extras.request("direct:json", "{\"answer\":42}")))) throw new AssertionError("Packaged JSON/Jackson/JSONPath failed");
            if (!"packaged-queue-ok".equals(extras.request("direct:queue", "work"))) throw new AssertionError("Packaged SEDA delivery failed");
        }
        try (var kamelet = new CamelRouteRuntime("packaged-kamelet", new CamelRouteResource("""
            - route:
                from:
                  uri: direct:kamelet
                  steps:
                    - to: kamelet:packaged-action/one?prefix=packaged-
            """), context -> {
                try { CamelKamelets.loadYaml((org.apache.camel.impl.DefaultCamelContext) context, "packaged-action", """
                    apiVersion: camel.apache.org/v1
                    kind: Kamelet
                    metadata:
                      name: ignored-by-native-resource-name
                    spec:
                      definition:
                        title: Packaged proof
                        required: [prefix]
                        properties:
                          prefix:
                            type: string
                      template:
                        from:
                          uri: kamelet:source
                          steps:
                            - setBody:
                                simple: '{{prefix}}${body}'
                            - to: kamelet:sink
                    """); }
                catch (Exception failure) { throw new IllegalStateException(failure); }
            }, null, () -> {}, extensions.loader())) {
            if (!"packaged-ok".equals(kamelet.request("direct:kamelet", "ok"))) throw new AssertionError("Packaged native Kamelet template failed");
        }
        var engine = new IgnitionPlatformHttpEngine();
        try (var inbound = new CamelRouteRuntime("packaged-inbound", new CamelRouteResource("""
            - route:
                from:
                  uri: ignition-gateway-http:/probe?httpMethodRestrict=POST
                  steps:
                    - setBody:
                        constant: packaged-inbound-ok
            """), engine::install, null, () -> {}, extensions.loader())) {
            if (!"packaged-inbound-ok".equals(engine.request("/probe", null, java.util.Map.of()).body())) {
                throw new AssertionError("Packaged platform-http engine failed");
            }
        }
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/probe", exchange -> {
            byte[] response = "packaged-camel-ok".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            try (var output = exchange.getResponseBody()) {
                output.write(response);
            }
            exchange.close();
        });
        server.start();
        try (var runtime = new CamelRouteRuntime("packaged-probe", new CamelRouteResource("""
            - route:
                from:
                  uri: direct:probe
                  steps:
                    - setHeader:
                        name: probe
                        simple: '${body}'
                    - to:
                        uri: http://127.0.0.1:%d/probe
            """.formatted(server.getAddress().getPort())), context -> {}, null, () -> {}, extensions.loader())) {
            String response = new String(
                (byte[]) runtime.request("direct:probe", "probe"), StandardCharsets.UTF_8
            );
            if (!"packaged-camel-ok".equals(response)) {
                throw new AssertionError("Unexpected packaged route response: " + response);
            }
        } finally {
            server.stop(0);
        }
    }
}
