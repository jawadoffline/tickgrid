plugins {
    application
    alias(libs.plugins.javafx)
}

description = "Runnable demonstrations: the blotter, and the harnesses that prove the tests have teeth."

javafx {
    version = libs.versions.javafx.get()
    // swing is here only so the blotter can export a PNG of itself; the library does not need it.
    modules = listOf("javafx.controls", "javafx.graphics", "javafx.swing")
}

dependencies {
    implementation(project(":tickgrid-core"))

    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.launcher)
}

application {
    mainClass = "io.github.tickgrid.demo.BlotterLauncher"
}

tasks.named<JavaExec>("run") {
    // -Protate=250 retires and relists one instrument per feed thread every 250ms, which is how
    // slot reclamation is watched rather than merely tested. Off unless asked for: it changes what
    // the demo measures, and the figures in BENCHMARKS.md are all from the static universe.
    (project.findProperty("rotate") as String?)?.let { systemProperty("feed.rotate", it) }
    (project.findProperty("stopAfter") as String?)?.let { systemProperty("feed.stopAfter", it) }
}

/** Every console demo, as its own task, so `./gradlew tasks --group demo` lists what is runnable. */
fun demo(name: String, main: String, note: String) =
    tasks.register<JavaExec>(name) {
        group = "demo"
        description = note
        classpath = sourceSets["main"].runtimeClasspath
        mainClass = main
    }

demo("handshakeDemo", "io.github.tickgrid.demo.HandshakeDemo",
     "Runs the broken ingress variants side by side and counts the damage.")
demo("sortContractDemo", "io.github.tickgrid.demo.SortContractDemo",
     "Sorts against a live store until TimSort throws, then does it correctly.")
demo("throughputProbe", "io.github.tickgrid.demo.ThroughputProbe",
     "Ingestion rate, allocation and conflation. Indicative, not JMH.")
demo("storeProbe", "io.github.tickgrid.demo.StoreProbe",
     "What a given capacity actually commits, and apply-path allocation.")

/** Renders docs/social-card.png at the 1280x640 GitHub wants for a repository preview. */
tasks.register<JavaExec>("socialCard") {
    group = "demo"
    description = "Renders the repository social preview card."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass = "io.github.tickgrid.demo.SocialCardLauncher"
    workingDir = rootProject.projectDir
    systemProperty("card.out", project.findProperty("out") ?: "docs/social-card.png")
}

/** The grid on live Binance market data. Public streams only -- no key, no account. */
tasks.register<JavaExec>("binance") {
    group = "demo"
    description = "Runs the grid against Binance's public market-data stream."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass = "io.github.tickgrid.demo.binance.BinanceLauncher"
    systemProperty("binance.top", project.findProperty("top") ?: "300")
    systemProperty("binance.quote", project.findProperty("quote") ?: "USDT")
    val shot = project.findProperty("screenshot") as String?
    if (shot != null) {
        systemProperty("screenshot", shot)
        systemProperty("screenshot.delay", project.findProperty("screenshotDelay") ?: "20000")
    }
}
