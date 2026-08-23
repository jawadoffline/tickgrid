package io.github.tickgrid.render;

import javafx.scene.paint.Color;

/**
 * Colours, metrics and the precomputed flash ramps.
 *
 * <p>The ramps are the reason this is a class rather than a handful of constants. Deriving a
 * translucent colour per flashing cell per frame — {@code colour.deriveColor(0, 1, 1, alpha)} —
 * allocates a {@code Color} every time, which on a grid mid-burst is thousands of objects a frame
 * and undoes the work the formatter does. Quantising alpha into a fixed ramp costs an array index
 * and is indistinguishable at 60 fps.
 */
public final class GridTheme {

    /** Alpha steps in each flash ramp. Beyond about 16 the eye cannot tell at 60 fps. */
    public static final int FLASH_STEPS = 24;

    public final Color background;
    public final Color rowEven;
    public final Color rowOdd;
    public final Color gridLine;
    public final Color headerBackground;
    public final Color headerText;
    public final Color headerRule;
    public final Color text;
    public final Color textDim;
    public final Color positive;
    public final Color negative;
    /** Translucent: the overlay tints the row, it does not cover the text under it. */
    public final Color selection;
    /** Translucent, for the same reason. */
    public final Color hover;
    public final Color focusRing;

    public final double rowHeight;
    public final double headerHeight;
    public final double cellPadding;
    public final String fontFamily;
    public final double fontSize;
    /** How long a cell stays lit after it changes. */
    public final int flashMillis;

    private final Color[] flashUp;
    private final Color[] flashDown;

    public static GridTheme dark() {
        return new GridTheme(
                Color.web("#12181c"), Color.web("#12181c"), Color.web("#171f24"),
                Color.web("#222c33"),
                Color.web("#1d272d"), Color.web("#7d8f99"), Color.web("#2c3941"),
                Color.web("#d7e0e5"), Color.web("#8b9aa3"),
                Color.web("#46b588"), Color.web("#e4785f"),
                Color.web("#55c4e6", 0.20),
                Color.web("#ffffff", 0.045), Color.web("#55c4e6"),
                16, 22, 6, "Consolas", 12, 320);
    }

    public static GridTheme light() {
        return new GridTheme(
                Color.web("#fbfcfc"), Color.web("#fbfcfc"), Color.web("#f1f4f5"),
                Color.web("#dde4e7"),
                Color.web("#eef2f4"), Color.web("#5c6f79"), Color.web("#cdd6da"),
                Color.web("#161f24"), Color.web("#5c6f79"),
                Color.web("#1e7f55"), Color.web("#b23a2a"),
                Color.web("#0a6e8c", 0.16),
                Color.web("#0a6e8c", 0.05), Color.web("#0a6e8c"),
                16, 22, 6, "Consolas", 12, 320);
    }

    @SuppressWarnings("checkstyle:ParameterNumber")
    public GridTheme(Color background, Color rowEven, Color rowOdd, Color gridLine,
                     Color headerBackground, Color headerText, Color headerRule,
                     Color text, Color textDim, Color positive, Color negative,
                     Color selection, Color hover, Color focusRing,
                     double rowHeight, double headerHeight, double cellPadding,
                     String fontFamily, double fontSize, int flashMillis) {
        this.background = background;
        this.rowEven = rowEven;
        this.rowOdd = rowOdd;
        this.gridLine = gridLine;
        this.headerBackground = headerBackground;
        this.headerText = headerText;
        this.headerRule = headerRule;
        this.text = text;
        this.textDim = textDim;
        this.positive = positive;
        this.negative = negative;
        this.selection = selection;
        this.hover = hover;
        this.focusRing = focusRing;
        this.rowHeight = rowHeight;
        this.headerHeight = headerHeight;
        this.cellPadding = cellPadding;
        this.fontFamily = fontFamily;
        this.fontSize = fontSize;
        this.flashMillis = flashMillis;

        this.flashUp = ramp(positive);
        this.flashDown = ramp(negative);
    }

    private static Color[] ramp(Color base) {
        Color[] steps = new Color[FLASH_STEPS];
        for (int i = 0; i < FLASH_STEPS; i++) {
            // Peak at ~55% so a flash tints the cell rather than obliterating the text on it.
            double alpha = 0.55 * (FLASH_STEPS - i) / (double) FLASH_STEPS;
            steps[i] = Color.color(base.getRed(), base.getGreen(), base.getBlue(), alpha);
        }
        return steps;
    }

    /**
     * The flash tint for a cell of the given age, or {@code null} once it has faded out.
     *
     * @param direction {@code +1} up, {@code -1} down, {@code 0} never changed
     */
    public Color flashColor(int ageMillis, int direction) {
        if (direction == 0 || ageMillis < 0 || ageMillis >= flashMillis) return null;
        int step = ageMillis * FLASH_STEPS / flashMillis;
        if (step >= FLASH_STEPS) return null;
        return direction > 0 ? flashUp[step] : flashDown[step];
    }
}
