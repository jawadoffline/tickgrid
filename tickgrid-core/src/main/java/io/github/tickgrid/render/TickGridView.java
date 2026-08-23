package io.github.tickgrid.render;

import io.github.tickgrid.ingress.ConflatingIngress;
import io.github.tickgrid.store.ColumnStore;
import io.github.tickgrid.view.ViewModel;
import io.github.tickgrid.view.ViewSnapshot;
import javafx.animation.AnimationTimer;
import javafx.scene.canvas.Canvas;
import javafx.scene.control.ScrollBar;
import javafx.geometry.Orientation;
import javafx.scene.input.KeyCode;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.Region;
import javafx.stage.Screen;

import java.util.List;

/**
 * The grid control: three canvases, two scrollbars, one frame loop.
 *
 * <h2>The frame loop</h2>
 * One {@link AnimationTimer} does everything in pulse order — sample the flash clock, drain the
 * ingress under a time budget, then repaint whatever is dirty. Draining here rather than on a timer
 * of its own is what makes a frame coherent: every row applied this frame is drawn this frame, and
 * a burst that exceeds the budget defers rather than stretching the frame.
 *
 * <h2>Not repainting</h2>
 * A frame is dirty if data was applied, the view was reordered, the viewport moved or resized, the
 * pointer moved, or any visible cell is still inside its flash window. When none of those hold, the
 * loop does nothing at all. A blotter watching a closed market should not burn a core, and the
 * timestamp-based flash model means idleness falls out for free rather than needing to be arranged.
 *
 * <h2>Accessibility</h2>
 * A canvas is one node to a screen reader, so this control is not accessible. That is inherent to
 * the approach, not an oversight, and it is stated here rather than left to be discovered.
 */
public final class TickGridView extends Region {

    /** Slice of the frame given to the drain. Measured FX-thread paint cost is 0.14-0.29 ms, so
     *  this is a quarter of a budget that the renderer barely touches. */
    private static final long DRAIN_BUDGET_NANOS = 4_000_000L;

    /** How long reordering stays frozen after a click, so a row cannot jump between clicks. */
    private static final long FREEZE_DWELL_NANOS = 400_000_000L;

    private final ColumnStore store;
    private final ViewModel viewModel;
    private final ConflatingIngress<?, ?> ingress;
    private final GridRenderer renderer;
    private final Viewport viewport = new Viewport();

    private final Canvas bodyCanvas = new Canvas();
    private final Canvas overlayCanvas = new Canvas();
    private final Canvas headerCanvas = new Canvas();
    private final ScrollBar vScroll = new ScrollBar();
    private final ScrollBar hScroll = new ScrollBar();

    private double scale = 1;
    private int selectedSlot = -1;
    private int hoverRow = -1;
    private boolean pointerDown;
    private long freezeUntilNanos;

    private long lastGeneration = -1;
    private long lastStoreEpoch = -1;
    private boolean bodyDirty = true;
    private boolean headerDirty = true;
    private boolean overlayDirty = true;

    private AnimationTimer timer;
    private long framesPainted;
    private long framesSkipped;

    public TickGridView(ColumnStore store, ViewModel viewModel,
                        ConflatingIngress<?, ?> ingress,
                        GridTheme theme, List<GridColumn> columns) {
        this.store = store;
        this.viewModel = viewModel;
        this.ingress = ingress;
        this.renderer = new GridRenderer(theme, columns);

        viewport.setRowHeight(theme.rowHeight);
        viewport.setHeaderHeight(theme.headerHeight);
        viewport.setContentWidth(renderer.contentWidth());

        vScroll.setOrientation(Orientation.VERTICAL);
        hScroll.setOrientation(Orientation.HORIZONTAL);
        getChildren().addAll(bodyCanvas, overlayCanvas, headerCanvas, vScroll, hScroll);

        setFocusTraversable(true);
        wireInput();
    }

    public Viewport viewport()      { return viewport; }
    public GridRenderer renderer()  { return renderer; }
    public long framesPainted()     { return framesPainted; }
    public long framesSkipped()     { return framesSkipped; }
    public int selectedSlot()       { return selectedSlot; }

    public void start() {
        if (timer != null) return;
        timer = new AnimationTimer() {
            @Override public void handle(long now) {
                tick(now);
            }
        };
        timer.start();
    }

    public void stop() {
        if (timer != null) {
            timer.stop();
            timer = null;
        }
    }

    // ------------------------------------------------------------ frame loop

    private void tick(long now) {
        store.beginFrame();
        final int applied = ingress.drain(DRAIN_BUDGET_NANOS, store.applier());

        if (pointerDown) {
            freezeUntilNanos = now + FREEZE_DWELL_NANOS;
        } else if (viewModel.isFrozen() && now > freezeUntilNanos) {
            viewModel.setFrozen(false);
        }

        final ViewSnapshot view = viewModel.snapshot();
        if (view.generation() != lastGeneration || view.count() != viewport.rowCount()) {
            lastGeneration = view.generation();
            viewport.setRowCount(view.count());
            syncScrollBars();
            bodyDirty = true;
            overlayDirty = true;
        }
        if (store.epoch() != lastStoreEpoch) {
            lastStoreEpoch = store.epoch();
            bodyDirty = true;
        }
        if (applied > 0 || renderer.isFlashActive()) {
            bodyDirty = true;
        }

        if (!bodyDirty && !headerDirty && !overlayDirty) {
            framesSkipped++;
            return;
        }

        if (bodyDirty) {
            renderer.paintBody(bodyCanvas.getGraphicsContext2D(), store, view, viewport, scale);
            bodyDirty = false;
        }
        if (headerDirty) {
            renderer.paintHeader(headerCanvas.getGraphicsContext2D(), viewport,
                    viewModel.sort(), scale);
            headerDirty = false;
        }
        if (overlayDirty || bodyDirty) {
            renderer.paintOverlay(overlayCanvas.getGraphicsContext2D(), view, viewport,
                    selectedSlot, hoverRow, isFocused(), scale);
            overlayDirty = false;
        }
        framesPainted++;
    }

    // ----------------------------------------------------------------- input

    private void wireInput() {
        setOnScroll(e -> {
            if (viewport.setScrollY(viewport.scrollY() - e.getDeltaY())) {
                afterScroll();
            }
            if (e.getDeltaX() != 0 && viewport.setScrollX(viewport.scrollX() - e.getDeltaX())) {
                afterScroll();
            }
        });

        vScroll.valueProperty().addListener((obs, old, value) -> {
            if (viewport.setScrollY(value.doubleValue())) afterScroll();
        });
        hScroll.valueProperty().addListener((obs, old, value) -> {
            if (viewport.setScrollX(value.doubleValue())) afterScroll();
        });

        setOnMousePressed(e -> {
            requestFocus();
            pointerDown = true;
            // Freeze immediately: a row must not move between press and release.
            viewModel.setFrozen(true);

            if (viewport.isInHeader(e.getY())) {
                if (e.getButton() == MouseButton.PRIMARY) {
                    int index = renderer.columnAt(e.getX(), viewport.scrollX());
                    if (index >= 0) {
                        viewModel.toggleSort(renderer.columns().get(index).storeColumn());
                        headerDirty = true;
                    }
                }
                return;
            }
            selectRowAt(e.getY());
        });

        setOnMouseReleased(e -> pointerDown = false);

        setOnMouseMoved(e -> {
            int row = viewport.rowAt(e.getY());
            if (row != hoverRow) {
                hoverRow = row;
                overlayDirty = true;
            }
        });
        setOnMouseExited(e -> {
            if (hoverRow != -1) {
                hoverRow = -1;
                overlayDirty = true;
            }
        });

        focusedProperty().addListener((obs, old, focused) -> overlayDirty = true);
        setOnKeyPressed(e -> {
            if (handleKey(e.getCode())) e.consume();
        });
    }

    private void selectRowAt(double y) {
        final ViewSnapshot view = viewModel.snapshot();
        final int row = viewport.rowAt(y);
        final int slot = row >= 0 && row < view.count() ? view.slotAt(row) : -1;
        if (slot != selectedSlot) {
            selectedSlot = slot;
            overlayDirty = true;
        }
    }

    /**
     * Keyboard navigation. Selection is held as a slot and re-located through the snapshot, so it
     * survives a reorder rather than pointing at whatever row inherited its index.
     */
    private boolean handleKey(KeyCode code) {
        final ViewSnapshot view = viewModel.snapshot();
        if (view.count() == 0) return false;

        final int current = selectedSlot < 0 ? -1 : view.positionOf(selectedSlot);
        final int page = viewport.rowsPerPage();
        int target = switch (code) {
            case UP -> current < 0 ? view.count() - 1 : current - 1;
            case DOWN -> current < 0 ? 0 : current + 1;
            case PAGE_UP -> current < 0 ? 0 : current - page;
            case PAGE_DOWN -> current < 0 ? 0 : current + page;
            case HOME -> 0;
            case END -> view.count() - 1;
            default -> Integer.MIN_VALUE;
        };
        if (target == Integer.MIN_VALUE) return false;

        target = Math.max(0, Math.min(view.count() - 1, target));
        selectedSlot = view.slotAt(target);
        overlayDirty = true;
        if (viewport.ensureRowVisible(target)) afterScroll();
        return true;
    }

    private void afterScroll() {
        bodyDirty = true;
        overlayDirty = true;
        headerDirty = true;                    // the header scrolls horizontally with the body
        syncScrollBars();
    }

    private void syncScrollBars() {
        vScroll.setMax(viewport.maxScrollY());
        // JavaFX sizes the thumb as visibleAmount / (max - min + visibleAmount). With the value
        // range being maxScroll = content - body, passing bodyHeight makes that resolve to
        // body/content exactly -- the fraction of the content on screen.
        vScroll.setVisibleAmount(viewport.bodyHeight());
        vScroll.setUnitIncrement(viewport.rowHeight());
        vScroll.setBlockIncrement(viewport.bodyHeight());
        if (vScroll.getValue() != viewport.scrollY()) vScroll.setValue(viewport.scrollY());
        vScroll.setVisible(viewport.maxScrollY() > 0);

        hScroll.setMax(viewport.maxScrollX());
        hScroll.setVisibleAmount(viewport.width());
        hScroll.setUnitIncrement(40);
        hScroll.setBlockIncrement(viewport.width());
        if (hScroll.getValue() != viewport.scrollX()) hScroll.setValue(viewport.scrollX());
        hScroll.setVisible(viewport.maxScrollX() > 0);
    }

    // ---------------------------------------------------------------- layout

    @Override
    protected void layoutChildren() {
        this.scale = Screen.getPrimary().getOutputScaleX();

        final double barSize = 12;
        final boolean needsV = viewport.maxScrollY() > 0;
        final boolean needsH = viewport.maxScrollX() > 0;
        final double w = Math.max(0, getWidth() - (needsV ? barSize : 0));
        final double h = Math.max(0, getHeight() - (needsH ? barSize : 0));

        viewport.setSize(w, h);
        viewport.setContentWidth(renderer.contentWidth());

        resizeCanvas(bodyCanvas, w, h);
        resizeCanvas(overlayCanvas, w, h);
        resizeCanvas(headerCanvas, w, viewport.headerHeight());
        headerCanvas.relocate(0, 0);

        vScroll.resizeRelocate(w, viewport.headerHeight(), barSize, h - viewport.headerHeight());
        hScroll.resizeRelocate(0, h, w, barSize);

        syncScrollBars();
        // A canvas is cleared by a resize, so a layout pass always costs a full repaint.
        bodyDirty = headerDirty = overlayDirty = true;
    }

    private static void resizeCanvas(Canvas canvas, double w, double h) {
        if (canvas.getWidth() != w) canvas.setWidth(w);
        if (canvas.getHeight() != h) canvas.setHeight(h);
    }

    @Override protected double computePrefWidth(double height) {
        return renderer.contentWidth() + 12;
    }

    @Override protected double computePrefHeight(double width) {
        return viewport.headerHeight() + viewport.rowHeight() * 30;
    }
}
