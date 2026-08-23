package io.github.tickgrid.demo;

import javafx.application.Application;

/** Separate entry point so the app class isn't the JVM main class (classpath + module-path mix). */
public final class SocialCardLauncher {
    public static void main(String[] args) {
        Application.launch(SocialCard.class, args);
    }
}
