plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
}

kotlin {
    jvmToolchain(21)
}

application {
    mainClass.set("com.pennywiseai.sync.ApplicationKt")
}

dependencies {
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.server.status.pages)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.sqlite.jdbc)
    implementation(libs.logback.classic)

    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}

fun npmAvailable(): Boolean {
    val path = System.getenv("PATH") ?: return false
    val names = if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) {
        listOf("npm.cmd", "npm.exe")
    } else {
        listOf("npm")
    }
    return path.split(java.io.File.pathSeparator).any { dir ->
        names.any { java.io.File(dir, it).isFile }
    }
}

val dashboardDir = rootProject.layout.projectDirectory.dir("dashboard")

tasks.register<Exec>("buildDashboard") {
    group = "build"
    description = "Build the desktop dashboard SPA when Node.js/npm is on PATH"
    workingDir = dashboardDir.asFile
    onlyIf {
        npmAvailable() && dashboardDir.asFile.resolve("package.json").isFile
    }
    commandLine("npm", "install")
    doLast {
        exec {
            workingDir = dashboardDir.asFile
            commandLine("npm", "run", "build")
        }
    }
    inputs.files(
        dashboardDir.file("package.json"),
        dashboardDir.file("vite.config.ts"),
        dashboardDir.file("tsconfig.json"),
        dashboardDir.file("index.html")
    )
    inputs.dir(dashboardDir.dir("src"))
    outputs.dir(dashboardDir.dir("dist"))
}

tasks.named("run") {
    dependsOn("buildDashboard")
}

tasks.register<Jar>("fatJar") {
    group = "build"
    archiveClassifier.set("all")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    dependsOn("buildDashboard")
    manifest {
        attributes["Main-Class"] = "com.pennywiseai.sync.ApplicationKt"
    }
    from(sourceSets.main.get().output)
    dependsOn(configurations.runtimeClasspath)
    from({
        configurations.runtimeClasspath.get().filter { it.exists() }.map { if (it.isDirectory) it else zipTree(it) }
    })
}
