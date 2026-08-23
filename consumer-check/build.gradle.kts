// A stand-in for somebody else's application. It resolves TickGrid the way Maven Central consumers
// will -- by coordinate, from a repository -- rather than by project reference, which is the only
// way to catch a POM that compiles here and not anywhere else.
plugins {
    application
    id("org.openjfx.javafxplugin") version "0.1.0"
}

repositories {
    mavenLocal()
    mavenCentral()
}

javafx {
    version = "21.0.5"
    modules = listOf("javafx.controls", "javafx.graphics")
}

dependencies {
    implementation("io.github.tickgrid:tickgrid:0.1.0-SNAPSHOT")
}

java {
    toolchain { languageVersion = JavaLanguageVersion.of(21) }
}

application {
    mainClass = "example.ConsumerCheck"
}
