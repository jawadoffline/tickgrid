plugins {
    application
    alias(libs.plugins.javafx)
    alias(libs.plugins.jmh)
}

description = "Frame time, staleness and CPU against TableView."

javafx {
    version = libs.versions.javafx.get()
    modules = listOf("javafx.controls", "javafx.graphics")
}

dependencies {
    implementation(project(":tickgrid-core"))
    implementation(libs.hdrhistogram)
}

/**
 * JMH settings for a run you would quote. One fork is enough to see a regression and not enough to
 * trust a number: a single JVM can land in an unlucky JIT state and hold it for the whole run, which
 * is exactly the failure a fork count exists to average out.
 *
 * Override for a quick check:
 *   ./gradlew :tickgrid-bench:jmh -Pjmh.fork=1 -Pjmh.iterations=3 -Pjmh.warmup=2
 */
jmh {
    fork = (project.findProperty("jmh.fork") as String?)?.toInt() ?: 3
    warmupIterations = (project.findProperty("jmh.warmup") as String?)?.toInt() ?: 5
    iterations = (project.findProperty("jmh.iterations") as String?)?.toInt() ?: 5
    timeOnIteration = "1s"
    warmup = "1s"
    // The design asks for allocation rate alongside throughput, and for the zero-allocation claim
    // that is the number that matters more than the throughput one.
    profilers = listOf("gc")
    resultFormat = "TEXT"
    (project.findProperty("jmh.include") as String?)?.let { includes = listOf(it) }
}

application {
    mainClass = "io.github.tickgrid.bench.BenchLauncher"
}

/**
 * One JVM per implementation, so JIT state and heap pressure do not carry between them.
 *
 * `./gradlew :tickgrid-bench:bench -Prows=200 -Prates=100000,500000`
 *
 * The per-implementation tasks are registered at configuration time and `bench` merely depends on
 * them; registering tasks from inside another task's configuration block is not allowed.
 */
val benchImpls = (project.findProperty("impls") as String?
    ?: "nullsink tickgrid tableview-batched tableview-naive").split(" ").filter { it.isNotBlank() }

val benchRunners = benchImpls.map { impl ->
    tasks.register<JavaExec>("bench_" + impl.replace('-', '_')) {
        group = "verification"
        description = "Frame-time matrix for $impl."
        classpath = sourceSets["main"].runtimeClasspath
        mainClass = "io.github.tickgrid.bench.BenchLauncher"
        systemProperty("bench.impl", impl)
        systemProperty("bench.rows", project.findProperty("rows") ?: "5000")
        systemProperty("bench.threads", project.findProperty("threads") ?: "2")
        systemProperty("bench.rates", project.findProperty("rates")
            ?: "10000,50000,100000,250000,500000")
        systemProperty("bench.seconds", project.findProperty("seconds") ?: "5")
        systemProperty("bench.warmupSeconds", project.findProperty("warmup") ?: "3")
    }
}

tasks.register("bench") {
    group = "verification"
    description = "Runs the frame-time matrix against every implementation."
    dependsOn(benchRunners)
}
