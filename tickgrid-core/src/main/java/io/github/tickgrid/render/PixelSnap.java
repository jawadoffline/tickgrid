package io.github.tickgrid.render;

/**
 * Aligns logical coordinates to device pixels.
 *
 * <p>This is the single most likely reason a canvas grid looks <i>worse</i> than {@code TableView}
 * on a real machine. At 125% or 150% Windows scaling one logical pixel is not one device pixel: a
 * row boundary at logical y=16.0 lands at device y=24.0 at 1.5x, which is fine, but a scroll offset
 * of 7.5 logical puts every boundary on a half-device-pixel and the renderer antialiases every grid
 * line into a grey smear. Text baselines drift the same way. The scene graph snaps for
 * {@code TableView} automatically; a {@code Canvas} gets nothing.
 *
 * <p>Two operations cover it. {@link #snap} moves a coordinate to the nearest device pixel boundary,
 * for fills and text. {@link #snapHairline} moves it to a device pixel <i>centre</i>, which is where
 * a one-pixel stroke has to sit to come out crisp rather than spread across two.
 */
public final class PixelSnap {

    private PixelSnap() {
    }

    /**
     * Rounds a logical coordinate so it maps exactly onto a device pixel boundary.
     *
     * @param scale the output scale, e.g. {@code Screen.getOutputScaleX()}
     */
    public static double snap(double value, double scale) {
        if (scale <= 0) return value;
        return Math.round(value * scale) / scale;
    }

    /**
     * Places a one-device-pixel stroke on a pixel centre.
     *
     * <p>A stroke is drawn centred on its path, so a hairline at a pixel boundary covers half of the
     * pixel on each side and both come out at 50%. Offsetting by half a device pixel puts it inside
     * one pixel, fully covered.
     */
    public static double snapHairline(double value, double scale) {
        if (scale <= 0) return value;
        return (Math.floor(value * scale) + 0.5) / scale;
    }

    /** The width, in logical units, of a stroke exactly one device pixel wide. */
    public static double hairlineWidth(double scale) {
        return scale <= 0 ? 1.0 : 1.0 / scale;
    }

    /**
     * Rounds a size up to a whole number of device pixels.
     *
     * <p>Used for row height: a fractional device height accumulates rounding down the grid, so row
     * 40 sits a pixel off from where {@code row * rowHeight} says it does, and hit testing stops
     * agreeing with what is drawn.
     */
    public static double snapSizeUp(double size, double scale) {
        if (scale <= 0) return size;
        return Math.ceil(size * scale) / scale;
    }
}
