import com.github.gradle.node.yarn.task.YarnTask
import com.github.gradle.node.npm.task.NpmTask

plugins {
    java
    id("com.github.node-gradle.node") version("7.0.2")
}

val projectOutput: String by extra("$buildDir/generated-resources/")

node {
    version.set("20.17.0")
    yarnVersion.set("1.22.18")
    npmVersion.set("8.5.5")
    download.set(true)
    nodeProjectDir.set(file(project.projectDir))
}

val yarnPackages by tasks.registering(YarnTask::class) {
    args.set(listOf("install", "--frozen-lockfile"))
    inputs.files(file("package.json"), file("yarn.lock"))
    outputs.dir(file("node_modules"))
    dependsOn("${project.path}:yarn", "${project.path}:npmSetup")
}

val webpack by tasks.registering(NpmTask::class) {
    group = "Ignition Module"
    description = "Builds the Gateway web UI bundle"
    args.set(listOf("run", "build"))
    dependsOn(yarnPackages)
    inputs.files(project.fileTree(project.projectDir).matching {
        exclude("**/node_modules/**", "**/build/**")
    })
    outputs.dir(file(projectOutput))
}

tasks.processResources {
    dependsOn(webpack)
}

val editorContract by tasks.registering(Exec::class) {
    group = "verification"
    description = "Checks the pipeline editor catalog against the options the Gateway publishes"
    dependsOn(yarnPackages)
    // The project's own pinned Node runs the test, so the result does not depend on whatever
    // happens to be on PATH.
    val nodeDirectory = node.computedNodeDir.get().asFile
    workingDir = project.projectDir
    commandLine(
        File(nodeDirectory, "bin/node").absolutePath,
        // The pinned Node 20 treats a bare .js file as CommonJS, and the editor sources are ES
        // modules that webpack handles. Module detection lets Node read them the same way without
        // renaming files or turning the whole package into ESM, which would break webpack.config.js.
        "--experimental-detect-module",
        "--test",
        "test/editor-contract.test.mjs",
        "test/library-integrations.test.mjs"
    )
    inputs.files(fileTree("src/camelRoutes"))
    inputs.files(fileTree("test"))
    // A test has no artifact to compare, so always run it.
    outputs.upToDateWhen { false }
}

tasks.check {
    dependsOn(editorContract)
}

sourceSets.main {
    output.dir(projectOutput, "builtBy" to webpack)
}
