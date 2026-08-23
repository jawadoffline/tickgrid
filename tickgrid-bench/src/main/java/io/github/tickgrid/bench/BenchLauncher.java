package io.github.tickgrid.bench;

import javafx.application.Application;

/** Separate entry point so the app class isn't the JVM main class (classpath + module-path mix). */
public final class BenchLauncher {
    public static void main(String[] args) {
        Application.launch(BenchApp.class, args);
    }
}
