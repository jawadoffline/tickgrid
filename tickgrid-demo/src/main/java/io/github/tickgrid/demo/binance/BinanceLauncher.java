package io.github.tickgrid.demo.binance;

import javafx.application.Application;

/** Separate entry point so the app class isn't the JVM main class (classpath + module-path mix). */
public final class BinanceLauncher {
    public static void main(String[] args) {
        Application.launch(BinanceDemo.class, args);
    }
}
