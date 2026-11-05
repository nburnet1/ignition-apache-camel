import java.security.MessageDigest
import java.util.zip.ZipFile

plugins {
    `java-library`
    id("me.champeau.jmh") version "0.7.2"
    id("com.github.johnrengelman.shadow") version "7.1.2"
}

java {
    toolchain {
        languageVersion.set(org.gradle.jvm.toolchain.JavaLanguageVersion.of(17))
    }
}

sourceSets.main {
    resources.srcDir(project(":web-ui").layout.buildDirectory.dir("generated-resources"))
}

// Optional starter libraries travel as intact JAR resources, not shaded classes or
// module-classloader dependencies. Operators can remove them through Extensions.
val includedCamelExtensions by configurations.creating {
    isCanBeConsumed = false
}
dependencies {
    includedCamelExtensions(platform("org.apache.camel:camel-bom:4.22.2"))
    listOf("direct", "log", "timer", "http", "seda", "bean", "file", "ftp", "scheduler", "quartz",
        "jackson", "jsonpath", "csv", "paho-mqtt5", "cron", "controlbus", "kamelet").forEach {
        includedCamelExtensions("org.apache.camel:camel-$it")
    }
}
// Optional component APIs are compile/test inputs, never part of the host runtime.
configurations.named("compileOnly") { extendsFrom(includedCamelExtensions) }
configurations.named("testImplementation") { extendsFrom(includedCamelExtensions) }
val managedExtensionArtifacts = providers.provider {
    val host = configurations.runtimeClasspath.get().files
    includedCamelExtensions.resolvedConfiguration.resolvedArtifacts.filter {
        it.file !in host && it.name != "jakarta.servlet-api"
    }
}
val includedExtensionResources = layout.buildDirectory.dir("generated-resources/camel-included")
val prepareIncludedExtensions by tasks.registering(Sync::class) {
    // Shadow expands *.jar inputs automatically. Keep resource bytes opaque;
    // the installer restores the original .jar filename on disk.
    from(managedExtensionArtifacts.map { artifacts -> artifacts.map { it.file } }) { rename { "$it.bin" } }
    into(includedExtensionResources.map { it.dir("camel-extensions") })
    doLast {
        val index = managedExtensionArtifacts.get().sortedBy { it.name }
            .joinToString("\n", postfix = "\n") { "${it.name}=${it.file.name}" }
        destinationDir.resolve("included.properties").writeText(index)
        // Record the resolved dependency closures, not guesses based on JAR filenames.
        val managed = managedExtensionArtifacts.get().associateBy { it.moduleVersion.id }
        val roots = includedCamelExtensions.resolvedConfiguration.firstLevelModuleDependencies
            .filter { it.moduleName in listOf("camel-direct", "camel-log", "camel-timer", "camel-http", "camel-seda", "camel-bean",
                "camel-file", "camel-ftp", "camel-scheduler", "camel-quartz", "camel-jackson", "camel-jsonpath", "camel-csv",
                "camel-paho-mqtt5", "camel-cron", "camel-controlbus", "camel-kamelet") }.associateBy { it.moduleName }
        val groups = roots.toSortedMap().flatMap { (name, root) ->
            val artifacts = root.allModuleArtifacts.toMutableSet()
            // Cron's Quartz service is a runtime/optional dependency, not a Maven compile edge.
            if (name == "camel-cron") artifacts.addAll(roots.getValue("camel-quartz").allModuleArtifacts)
            val dependencies = artifacts.filter { it.moduleVersion.id in managed }.map { it.name }.distinct().sorted()
            val file = managed.values.single { it.name == name }.file
            val sha = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
                .joinToString("") { "%02x".format(it.toInt() and 255) }
            listOf("$name=${dependencies.joinToString(",")}", "$name.sha256=$sha")
        }
        val libraryHashes = managed.values.sortedBy { it.name }.map { artifact ->
            val sha = MessageDigest.getInstance("SHA-256").digest(artifact.file.readBytes()).joinToString("") { "%02x".format(it.toInt() and 255) }
            "library.${artifact.name}.sha256=$sha"
        }
        destinationDir.resolve("groups.properties").writeText((groups + libraryHashes).joinToString("\n", postfix = "\n"))
    }
}
sourceSets.main { resources.srcDir(includedExtensionResources) }

// Opt-in acceptance infrastructure. These are staged through the authenticated
// Extensions API, not added to the default module or Gateway shared classpath.
val simulationExtensions by configurations.creating { isCanBeConsumed = false }
dependencies {
    simulationExtensions(platform("org.apache.camel:camel-bom:4.22.2"))
    simulationExtensions("org.apache.camel:camel-jms")
    simulationExtensions("org.apache.camel:camel-resilience4j")
    simulationExtensions("org.apache.activemq:artemis-jakarta-client:2.44.0")
}
tasks.register<Sync>("prepareSimulationExtensions") {
    group = "verification"
    description = "Resolve opt-in EIP simulation libraries for authenticated staging"
    from(provider {
        val existing = configurations.runtimeClasspath.get().files + includedCamelExtensions.files
        simulationExtensions.files.filter { it !in existing && !it.name.startsWith("slf4j-") }
    })
    into(layout.buildDirectory.dir("simulation-extensions"))
}


val bundledJar = tasks.named<com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar>("shadowJar") {
    // Camel discovers components, languages and loaders through service resources.
    // Dropping duplicate descriptors silently breaks the packaged module.
    mergeServiceFiles()
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
    // Use the Gateway's logging facade/provider, never a module-private SLF4J copy.
    exclude("org/slf4j/**")
    inputs.property("gatewayLoggingExternal", true)
}

tasks.named<Jar>("jar") {
    dependsOn(bundledJar)
    from(bundledJar.map { zipTree(it.archiveFile) })
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
}

dependencies {
    implementation(platform("org.apache.camel:camel-bom:4.22.2"))
    implementation("org.apache.camel:camel-core-engine")
    implementation("org.apache.camel:camel-core-languages")
    implementation("org.apache.camel:camel-yaml-dsl")
    implementation("org.apache.camel:camel-platform-http")
    implementation("org.apache.camel:camel-management") {
        // Management must not turn removable component libraries into fixed host components.
        exclude(group = "org.apache.camel", module = "camel-bean")
        exclude(group = "org.apache.camel", module = "camel-log")
    }
    // External Hawtio uses the embedded HTTP protocol handler; no servlet/server,
    // discovery, remote JMX proxy, history or notification agents are included.
    implementation("org.jolokia:jolokia-server-core:2.6.4")
    implementation("org.jolokia:jolokia-service-jmx:2.6.4")
    implementation("org.jolokia:jolokia-service-serializer:2.6.4")
    compileOnly("com.inductiveautomation.ignitionsdk:ignition-common:${rootProject.extra["sdk_version"]}")
    compileOnly("com.inductiveautomation.ignitionsdk:gateway-api:${rootProject.extra["sdk_version"]}")

    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("com.inductiveautomation.ignitionsdk:ignition-common:${rootProject.extra["sdk_version"]}")
    testImplementation("com.inductiveautomation.ignitionsdk:gateway-api:${rootProject.extra["sdk_version"]}")
    testImplementation("org.xerial:sqlite-jdbc:3.46.1.0")
    testImplementation("org.apache.camel:camel-jdbc")

    jmhImplementation("com.inductiveautomation.ignition:common:${rootProject.extra["sdk_version"]}")
    jmhImplementation("com.inductiveautomation.ignition:gateway-api:${rootProject.extra["sdk_version"]}")
    jmhImplementation("org.xerial:sqlite-jdbc:3.46.1.0")
}

jmh {
    warmupIterations.set(3)
    iterations.set(5)
    fork.set(1)
    timeOnIteration.set("2s")
    resultFormat.set("JSON")
    resultsFile.set(layout.buildDirectory.file("reports/jmh/results.json"))
}

tasks.named<Jar>("jmhJar") {
    isZip64 = true
}

// JMH's ordinary fat JAR keeps duplicate SPI ZIP entries instead of merging
// them. URLClassLoader then sees only one Camel TypeConverterLoader descriptor.
// Match the distributable's SPI-merging rule; never patch missing converters
// into the benchmark's CamelContext just to make the harness start.
val jmhServicesDirectory = layout.buildDirectory.dir("generated-resources/jmh-services")
val prepareJmhServices by tasks.registering {
    val runtime = configurations.named("jmhRuntimeClasspath")
    inputs.files(runtime)
    outputs.dir(jmhServicesDirectory)
    doLast {
        val descriptors = sortedMapOf<String, LinkedHashSet<String>>()
        for (jar in runtime.get().files.filter { it.isFile && it.extension == "jar" }.sortedBy { it.name }) {
            ZipFile(jar).use { zip ->
                for (entry in zip.entries().asSequence().filter { !it.isDirectory && it.name.startsWith("META-INF/services/") }) {
                    val lines = descriptors.getOrPut(entry.name) { linkedSetOf() }
                    zip.getInputStream(entry).bufferedReader().useLines { content ->
                        content.map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.forEach { lines.add(it) }
                    }
                }
            }
        }
        val directory = jmhServicesDirectory.get().asFile
        // Remove only obsolete files owned by this generated output directory.
        if (directory.exists()) directory.walkTopDown().filter { it.isFile }.forEach { file ->
            if (file.relativeTo(directory).invariantSeparatorsPath !in descriptors) file.delete()
        }
        descriptors.forEach { (name, lines) ->
            directory.resolve(name).apply { parentFile.mkdirs(); writeText(lines.joinToString("\n", postfix = "\n")) }
        }
    }
}
tasks.named<Jar>("jmhJar") {
    dependsOn(prepareJmhServices)
    from(jmhServicesDirectory)
    eachFile {
        if (path.startsWith("META-INF/services/") && !file.toPath().startsWith(jmhServicesDirectory.get().asFile.toPath())) exclude()
    }
    doLast {
        ZipFile(archiveFile.get().asFile).use { zip ->
            val entries = zip.entries().asSequence().filter { !it.isDirectory && it.name.startsWith("META-INF/services/") }.toList()
            check(entries.groupBy { it.name }.values.all { it.size == 1 }) { "JMH JAR contains duplicate service descriptors" }
            val converters = zip.getInputStream(zip.getEntry("META-INF/services/org/apache/camel/TypeConverterLoader"))
                .bufferedReader().use { it.readText() }
            check("org.apache.camel.converter.CamelBaseBulkConverterLoader" in converters
                && "org.apache.camel.converter.stream.CamelSupportBulkConverterLoader" in converters) {
                "JMH JAR lost Camel's core type converter discovery"
            }
        }
    }
}

tasks.test {
    useJUnitPlatform()
    val camelExamples = rootProject.layout.projectDirectory.dir("examples/camel")
    inputs.dir(camelExamples)
    systemProperty("camel.examplesDirectory", camelExamples.asFile.absolutePath)
    // The SDK's Jython JAR omits Lib/site.py; the real Gateway supplies its standard library.
    systemProperty("python.import.site", "false")
    systemProperty("python.cachedir", layout.buildDirectory.dir("jython-cache").get().asFile.absolutePath)
    // The editor catalog is verified alongside the Java tests so a single `check` covers both halves
    // of the module. Three defects in one connector dropdown shipped unnoticed because only the Java
    // side had coverage.
    dependsOn(":web-ui:editorContract")
}

tasks.processResources {
    dependsOn(":web-ui:webpack")
    dependsOn(prepareIncludedExtensions)
}

val packagedCamelTest = tasks.register<JavaExec>("packagedCamelTest") {
    group = "verification"
    description = "Verify Camel discovery using the bundled JAR and Gateway-provided logging API"
    dependsOn(tasks.named("jar"), tasks.named("testClasses"))
    javaLauncher.set(javaToolchains.launcherFor {
        languageVersion.set(JavaLanguageVersion.of(17))
    })
    classpath = files(tasks.named<Jar>("jar").flatMap { it.archiveFile }) +
        files(sourceSets.test.get().output.classesDirs) +
        files(configurations.testRuntimeClasspath.get().filter {
            it.name.startsWith("slf4j-api-") || it.name.startsWith("ia-gson-") || it.name.startsWith("jakarta.servlet-api-")
        })
    mainClass.set("com.noahburnette.ignition.apachecamel.camel.PackagedCamelProbe")
}

tasks.named("check") {
    dependsOn(packagedCamelTest)
}
