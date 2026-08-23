plugins {
    `java-library`
    `maven-publish`
    signing
    alias(libs.plugins.javafx)
}

description = "A canvas-rendered, virtualized data grid for JavaFX, built for real-time market data."

javafx {
    version = libs.versions.javafx.get()
    // controls is needed for ScrollBar and nothing else; graphics brings Canvas, base brings the
    // property machinery the toolkit itself needs.
    modules = listOf("javafx.controls", "javafx.graphics")
}

dependencies {
    // The only non-JavaFX runtime dependency. JCTools 4.x ships a real module-info
    // (module org.jctools.core), not merely an Automatic-Module-Name, so consumers can jlink.
    api(libs.jctools)

    // JavaFX has to be `api`, not `implementation`. The plugin defaults to `implementation`, which
    // publishes it at runtime scope -- and then a consumer cannot compile against TickGridView,
    // which extends Region, or against GridTheme, which exposes Color. Types in the public API
    // belong on the consumer's compile classpath.
    //
    // Declared without a platform classifier on purpose: openjfx's own POM carries OS-activated
    // profiles, and Gradle module metadata carries variants, so each consumer resolves the native
    // jar for the machine they are building on. Pinning a classifier here would publish a library
    // that only runs on Windows.
    val javafxVersion = libs.versions.javafx.get()
    api("org.openjfx:javafx-graphics:$javafxVersion")
    api("org.openjfx:javafx-controls:$javafxVersion")

    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.launcher)
}

java {
    withSourcesJar()
    withJavadocJar()
}

tasks.javadoc {
    title = "TickGrid $version API"
    (options as StandardJavadocDocletOptions).apply {
        windowTitle = "TickGrid API"
        // Makes Region, Canvas, Color and the JDK types clickable rather than bare names. Fetched
        // at build time; if either host is unreachable javadoc warns and carries on unlinked, which
        // is why this is not allowed to fail the build.
        links(
            "https://docs.oracle.com/en/java/javase/21/docs/api/",
            "https://openjfx.io/javadoc/21/"
        )
        bottom("Apache License 2.0. Measurements and their caveats in BENCHMARKS.md.")
    }
    isFailOnError = false
}

publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            from(components["java"])
            artifactId = "tickgrid"

            pom {
                name = "TickGrid"
                description = project.description
                url = "https://github.com/jawadoffline/tickgrid"
                inceptionYear = "2026"

                licenses {
                    license {
                        name = "The Apache License, Version 2.0"
                        url = "https://www.apache.org/licenses/LICENSE-2.0.txt"
                    }
                }
                developers {
                    developer {
                        id = "jawadoffline"
                        name = "Jawad Ali"
                    }
                }
                scm {
                    connection = "scm:git:https://github.com/jawadoffline/tickgrid.git"
                    developerConnection = "scm:git:ssh://git@github.com/jawadoffline/tickgrid.git"
                    url = "https://github.com/jawadoffline/tickgrid"
                }
            }
        }
    }

    repositories {
        maven {
            name = "sonatype"
            val releases = "https://s01.oss.sonatype.org/service/local/staging/deploy/maven2/"
            val snapshots = "https://s01.oss.sonatype.org/content/repositories/snapshots/"
            url = uri(if (version.toString().endsWith("SNAPSHOT")) snapshots else releases)
            credentials {
                username = providers.gradleProperty("sonatypeUsername").orNull
                password = providers.gradleProperty("sonatypePassword").orNull
            }
        }
    }
}

signing {
    // Signing is required to release to Central and pointless everywhere else, so it is switched on
    // by the presence of a key rather than by a flag someone has to remember.
    val key = providers.gradleProperty("signingKey").orNull
    val password = providers.gradleProperty("signingPassword").orNull
    isRequired = key != null && !version.toString().endsWith("SNAPSHOT")
    if (key != null) {
        useInMemoryPgpKeys(key, password)
        sign(publishing.publications["mavenJava"])
    }
}

/**
 * Proves the library can be linked into a consumer's runtime image.
 *
 * A published module that `requires` an automatic module cannot be jlinked, which breaks exactly
 * the desktop consumers this library targets -- anyone shipping a jpackage'd blotter. The
 * dependency list was chosen so this works; this task is what stops that quietly regressing.
 *
 * It opts out of the configuration cache rather than working around it. The alternatives were to
 * resolve the runtime classpath eagerly, which would slow every build for a task that runs on CI
 * and almost nowhere else, or to move one task into a build-logic project. An explicit, reasoned
 * opt-out on a single verification task is cheaper than either, and it keeps the cache on for
 * everything a developer actually runs.
 */
val jlinkImageDir: File = layout.buildDirectory.dir("jlink-image").get().asFile

val verifyJlink by tasks.registering(Exec::class) {
    group = "verification"
    description = "Links tickgrid and its dependencies into a runtime image with jlink."
    dependsOn(tasks.jar)
    notCompatibleWithConfigurationCache(
        "resolves the runtime classpath and the toolchain's jlink at execution time")

    // Deliberately no outputs.dir(): declaring it makes Gradle create the directory before the
    // task runs, and jlink refuses to write into one that already exists. The image is a
    // verification artefact, not something anything downstream consumes, so up-to-date checking
    // buys nothing here anyway -- this should re-run whenever it is asked for.
    doFirst {
        jlinkImageDir.deleteRecursively()
        val jlink = javaToolchains.launcherFor(java.toolchain).get()
            .metadata.installationPath.file("bin/jlink").asFile
        val modulePath = (configurations.runtimeClasspath.get().files +
                tasks.jar.get().archiveFile.get().asFile)
            .joinToString(File.pathSeparator) { it.absolutePath }

        commandLine(
            jlink.absolutePath,
            "--module-path", modulePath,
            "--add-modules", "io.github.tickgrid",
            "--output", jlinkImageDir.absolutePath,
            "--no-header-files", "--no-man-pages"
        )
    }
}
