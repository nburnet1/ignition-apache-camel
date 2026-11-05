package com.noahburnette.ignition.apachecamel.camel;

import com.inductiveautomation.ignition.common.gson.JsonObject;
import com.inductiveautomation.ignition.common.gson.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.jar.JarFile;

/** Trusted libraries, staged atomically on disk and frozen for one module lifecycle. */
public final class CamelExtensions implements AutoCloseable {
    public static final long MAX_JAR_BYTES = 64L * 1024 * 1024;
    private static final long MAX_EXPANDED_BYTES = 256L * 1024 * 1024;
    private static final int MAX_JARS = 128;
    private final Path directory;
    private final ClassLoader parent;
    private final URLClassLoader loader;
    private final Set<String> active;
    private final Map<String, JsonObject> schemas;
    private final String startupError;
    private final Map<String, String> included;
    private final Map<String, CamelExtensionGraph.Definition> groupDefinitions;
    private final Map<String, CamelExtensionGraph.Library> libraryMetadata = new java.util.concurrent.ConcurrentHashMap<>();

    public CamelExtensions(Path directory, ClassLoader parent) throws IOException {
        this.directory = directory;
        this.parent = parent;
        Files.createDirectories(directory);
        included = includedLibraries(parent);
        groupDefinitions = groupDefinitions(parent);
        // A previous explicit remove request is applied only at the next startup.
        for (String id : pendingRemovals()) {
                for (var entry : included.entrySet()) {
                    Path installed = directory.resolve("included-" + entry.getKey() + ".installed");
                    if ((Files.exists(installed) && Files.readString(installed).trim().equals(id)) || id.substring(65).equals(entry.getValue())) {
                        Files.writeString(directory.resolve("included-" + entry.getKey() + ".excluded"), "Removed by administrator; do not reinstall automatically\n");
                    }
                }
                Files.deleteIfExists(directory.resolve(id));
        }
        try (var files = Files.list(directory)) {
            for (Path marker : files.filter(path -> removalPlan(path) || path.getFileName().toString().matches("[a-f0-9]{64}-.+\\.jar\\.removed")).toList()) Files.delete(marker);
        }
        List<Path> jars = List.of();
        String invalid = null;
        try {
            seedIncluded();
            jars = staged();
            for (Path jar : jars) {
                if (Files.isSymbolicLink(jar) || Files.size(jar) > MAX_JAR_BYTES) throw new IllegalArgumentException("Invalid extension file: " + jar.getFileName());
                try (var input = Files.newInputStream(jar)) {
                    var digest = MessageDigest.getInstance("SHA-256"); byte[] buffer = new byte[16384]; int count;
                    while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
                    if (!jar.getFileName().toString().startsWith(HexFormat.of().formatHex(digest.digest()) + "-")) throw new IllegalArgumentException("Extension checksum mismatch: " + jar.getFileName());
                } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
            }
            inspectSet(jars);
        } catch (IOException | RuntimeException failure) {
            invalid = failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
            // Fail closed for extensions, but leave built-in routes and the repair UI available.
            jars = List.of();
        }
        startupError = invalid;
        active = jars.stream().map(path -> path.getFileName().toString()).collect(java.util.stream.Collectors.toUnmodifiableSet());
        loader = new ExtensionLoader(jars.stream().map(path -> {
            try { return path.toUri().toURL(); } catch (java.net.MalformedURLException impossible) { throw new IllegalArgumentException(impossible); }
        }).toArray(java.net.URL[]::new), parent) {
            @Override public java.net.URL getResource(String name) {
                var privateResource = findResource(name);
                return privateResource == null ? super.getResource(name) : privateResource;
            }
            @Override public java.util.Enumeration<java.net.URL> getResources(String name) throws IOException {
                var resources = new java.util.LinkedHashSet<>(java.util.Collections.list(findResources(name)));
                resources.addAll(java.util.Collections.list(parent.getResources(name)));
                return java.util.Collections.enumeration(resources);
            }
            @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                synchronized (getClassLoadingLock(name)) {
                    if (name.startsWith("com.noahburnette.ignition.apachecamel.gateway.optional.")) {
                        Class<?> existing = findLoadedClass(name);
                        if (existing != null) return existing;
                        try (var input = parent.getResourceAsStream(name.replace('.', '/') + ".class")) {
                            if (input == null) throw new ClassNotFoundException(name);
                            byte[] bytes = input.readNBytes(2 * 1024 * 1024 + 1);
                            if (bytes.length > 2 * 1024 * 1024) throw new ClassNotFoundException(name);
                            Class<?> adapter = defineClass(name, bytes, 0, bytes.length);
                            if (resolve) resolveClass(adapter);
                            return adapter;
                        } catch (IOException failure) { throw new ClassNotFoundException(name, failure); }
                    }
                    // Private extension dependencies must not accidentally bind to a different
                    // Jackson/HTTP/SSH/etc. version used internally by the Gateway. Core/SDK
                    // classes are excluded during inspection and always stay parent-owned.
                    Class<?> loaded = findLoadedClass(name);
                    if (loaded != null) return loaded;
                    if (findResource(name.replace('.', '/') + ".class") != null) {
                        Class<?> extension = findClass(name);
                        if (resolve) resolveClass(extension);
                        return extension;
                    }
                    try { return super.loadClass(name, resolve); }
                    catch (NoClassDefFoundError missing) {
                        // A bundled optional provider (e.g. Quartz's Cron service) may implement an
                        // API installed by an extension. The parent cannot see child-only APIs.
                        // Re-home only that otherwise-unloadable component implementation; keep
                        // Camel core, host classes and all successfully loaded classes parent-owned.
                        String dependency = missing.getMessage();
                        if (!name.startsWith("org.apache.camel.component.") || dependency == null
                            || findResource(dependency.replace('.', '/') + ".class") == null) throw missing;
                        try (var input = parent.getResourceAsStream(name.replace('.', '/') + ".class")) {
                            if (input == null) throw missing;
                            byte[] bytes = input.readNBytes(2 * 1024 * 1024 + 1);
                            if (bytes.length > 2 * 1024 * 1024) throw missing;
                            Class<?> provider = defineClass(name, bytes, 0, bytes.length);
                            if (resolve) resolveClass(provider);
                            return provider;
                        } catch (IOException failure) { throw new ClassNotFoundException(name, failure); }
                    }
                }
            }
        };
        var found = new LinkedHashMap<String, JsonObject>();
        for (var entry : CamelComponentCatalog.entries()) for (String scheme : entry.components()) {
            String location = "META-INF/org/apache/camel/component/" + scheme + "/" + scheme + ".json";
            try (var stream = parent.getResourceAsStream(location)) {
                if (stream != null) addSchema(found, parse(stream));
            }
        }
        for (Path jar : jars) try (var file = new JarFile(jar.toFile())) {
            var entries = file.entries();
            while (entries.hasMoreElements()) {
                var entry = entries.nextElement();
                if (isSchema(entry.getName())) try (var stream = file.getInputStream(entry)) { addSchema(found, parse(stream)); }
            }
        }
        schemas = Map.copyOf(found);
    }

    public ClassLoader loader() { return loader; }
    public static boolean ownsClass(ClassLoader loader, String name) {
        return loader instanceof ExtensionLoader extensions && extensions.findResource(name.replace('.', '/') + ".class") != null;
    }
    private abstract static class ExtensionLoader extends URLClassLoader {
        ExtensionLoader(java.net.URL[] urls, ClassLoader parent) { super(urls, parent); }
    }
    public String startupError() { return startupError; }
    public List<JsonObject> schemas() { return schemas.entrySet().stream().sorted(Map.Entry.comparingByKey()).map(entry -> {
        var copy = entry.getValue().deepCopy(); copy.getAsJsonObject("component").addProperty("scheme", entry.getKey()); return copy;
    }).toList(); }
    public JsonObject schema(String scheme) {
        var value = schemas.get(scheme);
        if (value == null) throw new IllegalArgumentException("Component metadata unavailable; install its compatible JARs and restart the Gateway");
        return value.deepCopy();
    }

    public synchronized List<Map<String, Object>> inventory() throws IOException {
        var names = new java.util.TreeSet<>(active);
        try (var files = Files.list(directory)) { files.filter(path -> path.getFileName().toString().matches("[a-f0-9]{64}-.+\\.jar")).forEach(path -> names.add(path.getFileName().toString())); }
        var result = new ArrayList<Map<String, Object>>();
        var removals = pendingRemovals();
        var includedIds = new java.util.HashSet<String>();
        for (String artifact : included.keySet()) {
            Path installed = directory.resolve("included-" + artifact + ".installed");
            if (Files.exists(installed)) includedIds.add(Files.readString(installed).trim());
        }
        for (String name : names) {
            boolean removed = removals.contains(name);
            result.add(Map.of("id", name, "name", name.substring(65), "sha256", name.substring(0, 64),
                "origin", includedIds.contains(name) ? "INCLUDED" : "UPLOADED",
                "bytes", Files.size(directory.resolve(name)), "state", removed ? "REMOVAL_PENDING" : active.contains(name) ? "ACTIVE" : startupError != null ? "BLOCKED" : "RESTART_REQUIRED",
                "artifact", libraryMetadata.computeIfAbsent(name, this::inspectMetadata).artifact(),
                "version", libraryMetadata.computeIfAbsent(name, this::inspectMetadata).version()));
        }
        return result;
    }

    public synchronized String upload(String name, InputStream input) throws Exception {
        if (name == null || !name.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,159}\\.jar")) throw new IllegalArgumentException("Choose a .jar file with a simple filename (letters, numbers, dots, underscores or hyphens)");
        if (staged().size() >= MAX_JARS) throw new IllegalArgumentException("At most 128 extension JARs are supported");
        Path temporary = Files.createTempFile(directory, ".upload-", ".tmp");
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            try (var output = Files.newOutputStream(temporary)) {
                byte[] buffer = new byte[16384]; long total = 0; int count;
                while ((count = input.read(buffer)) != -1) {
                    total += count;
                    if (total > MAX_JAR_BYTES) throw new IllegalArgumentException("JAR exceeds the 64 MiB limit");
                    digest.update(buffer, 0, count); output.write(buffer, 0, count);
                }
            }
            String id = HexFormat.of().formatHex(digest.digest()) + "-" + name;
            Path target = directory.resolve(id);
            if (Files.exists(target)) throw new IllegalArgumentException("This JAR already exists; pending removals take effect after restart");
            var candidates = new ArrayList<>(staged()); candidates.add(temporary);
            inspectSet(candidates);
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
            return id;
        } finally { Files.deleteIfExists(temporary); }
    }

    public synchronized void remove(String id) throws IOException {
        if (!validId(id)) throw new IllegalArgumentException("Unknown extension JAR");
        removeAll(List.of(id));
    }

    public synchronized void removeAll(List<String> ids) throws IOException {
        if (ids.isEmpty() || ids.size() > MAX_JARS) throw new IllegalArgumentException("Select 1–128 libraries");
        for (String id : ids) if (!validId(id) || !Files.isRegularFile(directory.resolve(id)) || Files.isSymbolicLink(directory.resolve(id))) throw new IllegalArgumentException("Unknown extension JAR");
        // Publish the whole decision atomically. The files remain readable to the active loader.
        Path temporary = Files.createTempFile(directory, ".removal-", ".tmp");
        try {
            Files.writeString(temporary, ids.stream().distinct().sorted().collect(java.util.stream.Collectors.joining("\n", "", "\n")));
            Files.move(temporary, directory.resolve("removal-" + java.util.UUID.randomUUID() + ".plan"), StandardCopyOption.ATOMIC_MOVE);
        } finally { Files.deleteIfExists(temporary); }
    }

    private List<Path> staged() throws IOException {
        var removals = pendingRemovals();
        try (var files = Files.list(directory)) {
            return files.filter(path -> path.getFileName().toString().matches("[a-f0-9]{64}-.+\\.jar"))
                .filter(path -> !removals.contains(path.getFileName().toString())).sorted().toList();
        }
    }

    private Set<String> pendingRemovals() throws IOException {
        var ids = new java.util.HashSet<String>();
        try (var files = Files.list(directory)) {
            for (Path path : files.toList()) {
                String name = path.getFileName().toString();
                if (name.endsWith(".removed") && validId(name.substring(0, name.length() - 8))) ids.add(name.substring(0, name.length() - 8));
                if (removalPlan(path)) {
                    if (Files.size(path) > 65536) throw new IOException("Removal plan exceeds limit");
                    for (String id : Files.readAllLines(path)) {
                        if (!validId(id)) throw new IOException("Invalid removal plan");
                        ids.add(id);
                    }
                }
            }
        }
        return Set.copyOf(ids);
    }
    private static boolean removalPlan(Path path) { return path.getFileName().toString().matches("removal-[a-f0-9-]{36}\\.plan"); }
    private static boolean validId(String id) { return id != null && id.matches("[a-f0-9]{64}-[A-Za-z0-9][A-Za-z0-9._-]{0,159}\\.jar"); }

    public synchronized CamelExtensionGraph graph() throws IOException {
        var files = new ArrayList<CamelExtensionGraph.Library>();
        for (var item : inventory()) {
            String id = item.get("id").toString();
            var info = libraryMetadata.computeIfAbsent(id, this::inspectMetadata);
            files.add(new CamelExtensionGraph.Library(id, info.name(), info.artifact(), info.version(), info.title(), info.schemes(), info.features(), item.get("state").toString()));
        }
        return new CamelExtensionGraph(files, groupDefinitions);
    }

    private CamelExtensionGraph.Library inspectMetadata(String id) {
        String name = id.substring(65), artifact = "", version = "", title = name;
        var schemes = new java.util.TreeSet<String>(); var features = new java.util.TreeSet<String>();
        var coordinates = new LinkedHashMap<String, String>();
        for (var entry : included.entrySet()) if (entry.getValue().equals(name)) artifact = entry.getKey();
        try (var jar = new JarFile(directory.resolve(id).toFile())) {
            var entries = jar.entries();
            int count = 0;
            while (entries.hasMoreElements()) {
                if (++count > 50000) throw new IOException("Too many metadata entries");
                var entry = entries.nextElement();
                if (entry.getName().startsWith("META-INF/maven/") && entry.getName().endsWith("/pom.properties")) {
                    var properties = new Properties(); try (var input = jar.getInputStream(entry)) {
                        byte[] data = input.readNBytes(65537);
                        if (data.length > 65536) throw new IOException("Oversized Maven metadata");
                        properties.load(new java.io.ByteArrayInputStream(data));
                    }
                    if (properties.getProperty("artifactId") != null) coordinates.put(properties.getProperty("artifactId"), properties.getProperty("version", ""));
                }
                if (entry.getName().startsWith("META-INF/org/apache/camel/") && entry.getName().endsWith(".json")) {
                    try (var input = jar.getInputStream(entry)) {
                        var schema = parse(input);
                        for (String kind : List.of("component", "language", "dataformat")) if (schema.has(kind)) {
                            var metadata = schema.getAsJsonObject(kind);
                            if (metadata.has("title")) title = metadata.get("title").getAsString();
                            if (kind.equals("component") && metadata.has("scheme")) schemes.addAll(List.of(metadata.get("scheme").getAsString().split(",")));
                            else if (metadata.has("name")) features.add(metadata.get("name").getAsString().toLowerCase(java.util.Locale.ROOT));
                        }
                    }
                }
            }
            if (artifact.isEmpty() && coordinates.size() == 1) artifact = coordinates.keySet().iterator().next();
            version = coordinates.getOrDefault(artifact, "");
        } catch (Exception ignored) { /* Broken libraries must remain visible/removable in the repair UI. */ }
        if (schemes.contains("paho-mqtt5")) title = "MQTT 5 · Paho";
        return new CamelExtensionGraph.Library(id, name, artifact, version, title, List.copyOf(schemes), List.copyOf(features), "");
    }

    private static Map<String, CamelExtensionGraph.Definition> groupDefinitions(ClassLoader parent) throws IOException {
        var result = new LinkedHashMap<String, CamelExtensionGraph.Definition>();
        try (var input = parent.getResourceAsStream("camel-extensions/groups.properties")) {
            if (input == null) return Map.of();
            var properties = new Properties(); properties.load(input);
            var libraryHashes = new LinkedHashMap<String, String>();
            for (String name : properties.stringPropertyNames()) if (name.startsWith("library.") && name.endsWith(".sha256")) libraryHashes.put(name.substring(8, name.length() - 7), properties.getProperty(name));
            for (String artifact : properties.stringPropertyNames()) if (!artifact.endsWith(".sha256")) {
                result.put(artifact, new CamelExtensionGraph.Definition(artifact, properties.getProperty(artifact + ".sha256", ""),
                    Set.copyOf(List.of(properties.getProperty(artifact).split(","))), Map.copyOf(libraryHashes)));
            }
        }
        return Map.copyOf(result);
    }

    private static Map<String, String> includedLibraries(ClassLoader parent) throws IOException {
        var entries = new LinkedHashMap<String, String>();
        try (var input = parent.getResourceAsStream("camel-extensions/included.properties")) {
            if (input == null) return Map.of();
            var properties = new Properties(); properties.load(input);
            for (String artifact : properties.stringPropertyNames().stream().sorted().toList()) {
                String name = properties.getProperty(artifact);
                if (!artifact.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}") || !name.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,159}\\.jar")) throw new IOException("Invalid included extension index");
                entries.put(artifact, name);
            }
        }
        return Map.copyOf(entries);
    }

    private void seedIncluded() throws IOException {
        for (var entry : included.entrySet()) {
            Path installed = directory.resolve("included-" + entry.getKey() + ".installed");
            if (Files.exists(installed) || Files.exists(directory.resolve("included-" + entry.getKey() + ".excluded"))) continue;
            try (var input = parent.getResourceAsStream("camel-extensions/" + entry.getValue() + ".bin")) {
                if (input == null) throw new IOException("Missing included extension: " + entry.getValue());
                byte[] bytes = input.readNBytes((int) MAX_JAR_BYTES + 1);
                if (bytes.length > MAX_JAR_BYTES) throw new IOException("Included extension exceeds upload limit");
                String id = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)) + "-" + entry.getValue();
                // Adopt an identical manually uploaded copy, never duplicate its classes.
                if (!Files.exists(directory.resolve(id))) {
                    Path temporary = Files.createTempFile(directory, ".included-", ".tmp");
                    try {
                        Files.write(temporary, bytes);
                        Files.move(temporary, directory.resolve(id), StandardCopyOption.ATOMIC_MOVE);
                    } finally { Files.deleteIfExists(temporary); }
                }
                Files.writeString(installed, id + "\n");
            } catch (Exception failure) { throw new IOException("Could not install included extension " + entry.getValue() + ": " + failure.getMessage(), failure); }
        }
    }

    private void inspectSet(List<Path> jars) throws IOException {
        if (jars.size() > MAX_JARS) throw new IllegalArgumentException("Too many extension JARs");
        var classes = new java.util.HashSet<String>();
        var schemes = new java.util.HashSet<String>();
        var descriptors = new java.util.HashSet<String>();
        for (Path path : jars) {
            if (Files.isSymbolicLink(path) || Files.size(path) > MAX_JAR_BYTES) throw new IllegalArgumentException("Invalid extension file");
            try (var jar = new JarFile(path.toFile(), true)) {
                var jarClasses = new java.util.HashSet<String>();
                if (jar.getManifest() != null && jar.getManifest().getMainAttributes().getValue("Class-Path") != null) throw new IllegalArgumentException("Manifest Class-Path is unsupported; upload dependency JARs explicitly");
                var entries = jar.entries(); long total = 0; int count = 0;
                while (entries.hasMoreElements()) {
                    var entry = entries.nextElement(); String name = entry.getName();
                    if (++count > 50000) throw new IllegalArgumentException("Too many JAR entries");
                    if (entry.isDirectory()) continue;
                    if (name.equals("META-INF/services/org.slf4j.spi.SLF4JServiceProvider") || name.equals("org/slf4j/impl/StaticLoggerBinder.class")) throw new IllegalArgumentException("Use the Gateway's logging provider; do not upload an SLF4J provider");
                    // Read bounded data even when ZIP sizes are unknown or misleading.
                    byte[] head;
                    try (var stream = jar.getInputStream(entry)) {
                        head = stream.readNBytes(8); total += head.length;
                        byte[] buffer = new byte[16384]; int length;
                        while ((length = stream.read(buffer)) != -1) {
                            total += length;
                            if (total > MAX_EXPANDED_BYTES) throw new IllegalArgumentException("Expanded JAR exceeds 256 MiB");
                        }
                    }
                    String className = name.replaceFirst("^META-INF/versions/[0-9]+/", "");
                    if (name.endsWith(".class") && !name.endsWith("module-info.class")) {
                        if (head.length < 8 || head[0] != (byte) 0xca || head[1] != (byte) 0xfe) throw new IllegalArgumentException("Invalid Java class");
                        if (!name.startsWith("META-INF/versions/") && ((head[6] & 255) << 8 | (head[7] & 255)) > 61) throw new IllegalArgumentException("Extension requires a Java version newer than 17");
                        if (className.startsWith("java/") || ClassLoader.getPlatformClassLoader().getResource(className) != null || className.startsWith("jakarta/servlet/")
                            || className.startsWith("com/inductiveautomation/") || className.startsWith("org/slf4j/") || className.startsWith("org/python/")
                            || className.startsWith("com/noahburnette/ignition/") || hostOwns(className)) throw new IllegalArgumentException("Extension duplicates a protected or engine class: " + className);
                        jarClasses.add(className);
                    }
                    if (name.startsWith("META-INF/services/org/apache/camel/component/") && !name.substring("META-INF/services/org/apache/camel/component/".length()).contains("/")) {
                        String scheme = name.substring("META-INF/services/org/apache/camel/component/".length());
                        if (Set.of("ignition-tag", "ignition-script", "ignition-message", "ignition-perspective-message", "ignition-gateway-http", "platform-http", "properties").contains(scheme)
                            || parent.getResource(name) != null || !descriptors.add(scheme)) throw new IllegalArgumentException("Duplicate or reserved component descriptor: " + scheme);
                    }
                    if (name.startsWith("META-INF/maven/org.apache.camel/") && name.endsWith("/pom.properties")) {
                        var properties = new Properties(); try (var stream = jar.getInputStream(entry)) { properties.load(stream); }
                        if (!CamelComponentCatalog.VERSION.equals(properties.getProperty("version"))) throw new IllegalArgumentException("Camel extensions must match runtime version " + CamelComponentCatalog.VERSION);
                    }
                    if (isSchema(name)) try (var stream = jar.getInputStream(entry)) {
                        var schema = parse(stream);
                        if (!schema.has("component")) continue; // Some data formats share this directory.
                        String scheme = schema.getAsJsonObject("component").get("scheme").getAsString();
                        for (String value : scheme.split(",")) {
                            if (Set.of("ignition-tag", "ignition-script", "ignition-message", "ignition-perspective-message", "ignition-gateway-http", "platform-http", "properties").contains(value)
                                || parent.getResource("META-INF/services/org/apache/camel/component/" + value) != null
                                || !schemes.add(value)) throw new IllegalArgumentException("Duplicate or reserved component scheme: " + value);
                        }
                    }
                }
                for (String name : jarClasses) if (!classes.add(name)) throw new IllegalArgumentException("Duplicate class across extension JARs: " + name);
            }
        }
    }

    private static boolean isSchema(String name) { return name.matches("META-INF/org/apache/camel/component/[^/]+/[^/]+\\.json"); }
    private boolean hostOwns(String resource) {
        var location = parent.getResource(resource);
        if (location == null) return false;
        // Camel core has one identity even in test harnesses with unshaded dependency JARs.
        if (resource.startsWith("org/apache/camel/") && !resource.startsWith("org/apache/camel/component/")) return true;
        if (resource.startsWith("org/jolokia/")) return true;
        var host = CamelExtensions.class.getProtectionDomain().getCodeSource().getLocation().toExternalForm();
        return location.toExternalForm().startsWith("jar:" + host + "!/") || location.toExternalForm().startsWith(host);
    }
    private static JsonObject parse(InputStream stream) throws IOException {
        byte[] bytes = stream.readNBytes(2 * 1024 * 1024 + 1);
        if (bytes.length > 2 * 1024 * 1024) throw new IllegalArgumentException("Component metadata exceeds 2 MiB");
        return JsonParser.parseString(new String(bytes, java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
    }
    private static void addSchema(Map<String, JsonObject> schemas, JsonObject schema) {
        if (!schema.has("component")) return;
        for (String scheme : schema.getAsJsonObject("component").get("scheme").getAsString().split(",")) {
            if (schemas.putIfAbsent(scheme, schema) != null) throw new IllegalArgumentException("Duplicate component metadata: " + scheme);
        }
    }
    @Override public void close() throws IOException { loader.close(); }
}
