/**
 * A canvas-rendered, virtualized data grid for JavaFX, built for real-time market data.
 *
 * <p>Four packages, in pipeline order: {@link io.github.tickgrid.ingress} takes messages from any
 * thread and conflates them, {@link io.github.tickgrid.store} holds them columnar,
 * {@link io.github.tickgrid.view} turns them into a render order, and
 * {@link io.github.tickgrid.render} paints it.
 */
module io.github.tickgrid {
    requires javafx.graphics;
    requires javafx.controls;
    // A real named module as of JCTools 4.x, so this does not block a consumer's jlink image.
    requires transitive org.jctools.core;

    exports io.github.tickgrid.ingress;
    exports io.github.tickgrid.store;
    exports io.github.tickgrid.view;
    exports io.github.tickgrid.render;
}
