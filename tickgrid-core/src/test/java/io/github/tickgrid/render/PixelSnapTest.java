package io.github.tickgrid.render;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PixelSnapTest {

    @Test
    void snappingIsAnIdentityAtIntegerScale() {
        assertEquals(10.0, PixelSnap.snap(10.0, 1.0), 1e-9);
        assertEquals(10.0, PixelSnap.snap(10.4, 1.0), 1e-9);
        assertEquals(11.0, PixelSnap.snap(10.6, 1.0), 1e-9);
    }

    @Test
    void snappingLandsOnDevicePixelsAtFractionalScale() {
        // At 1.5x, only multiples of 2/3 logical land on a whole device pixel.
        for (double v : new double[]{0.1, 3.3, 7.77, 100.49, 512.5}) {
            double snapped = PixelSnap.snap(v, 1.5);
            double device = snapped * 1.5;
            assertEquals(Math.round(device), device, 1e-9,
                    v + " snapped to " + snapped + " which is device " + device);
        }
    }

    @Test
    void snappingIsCorrectAt125And200Percent() {
        for (double scale : new double[]{1.25, 1.75, 2.0, 2.5, 3.0}) {
            for (double v : new double[]{0.0, 1.1, 16.4, 333.7}) {
                double device = PixelSnap.snap(v, scale) * scale;
                assertEquals(Math.round(device), device, 1e-9,
                        "scale " + scale + " value " + v);
            }
        }
    }

    @Test
    void snappingNeverMovesMoreThanHalfADevicePixel() {
        for (double scale : new double[]{1.0, 1.25, 1.5, 2.0}) {
            for (double v = 0; v < 40; v += 0.137) {
                double moved = Math.abs(PixelSnap.snap(v, scale) - v);
                assertTrue(moved <= 0.5 / scale + 1e-9,
                        "moved " + moved + " at scale " + scale);
            }
        }
    }

    /**
     * A stroke is centred on its path, so a hairline sitting on a pixel boundary covers half of each
     * neighbouring pixel and both come out at 50% grey. It has to sit on a pixel centre.
     */
    @Test
    void hairlinesLandOnPixelCentres() {
        for (double scale : new double[]{1.0, 1.5, 2.0}) {
            for (double v : new double[]{0.0, 3.2, 17.9, 240.0}) {
                double device = PixelSnap.snapHairline(v, scale) * scale;
                assertEquals(0.5, device - Math.floor(device), 1e-9,
                        "scale " + scale + " value " + v + " landed at device " + device);
            }
        }
    }

    @Test
    void hairlineWidthIsOneDevicePixel() {
        assertEquals(1.0, PixelSnap.hairlineWidth(1.0), 1e-9);
        assertEquals(0.5, PixelSnap.hairlineWidth(2.0), 1e-9);
        assertEquals(1.0 / 1.5, PixelSnap.hairlineWidth(1.5), 1e-9);
    }

    /**
     * Row height must be a whole number of device pixels or the error accumulates: at 1.5x a
     * 16-logical row is 24 device px exactly, but 15 would be 22.5 and by row 40 the painted grid
     * and the hit-test arithmetic would be twenty pixels apart.
     */
    @Test
    void sizesRoundUpToWholeDevicePixels() {
        assertEquals(16.0, PixelSnap.snapSizeUp(16.0, 1.5), 1e-9);       // 24 device, already whole
        double snapped = PixelSnap.snapSizeUp(15.0, 1.5);
        assertEquals(Math.round(snapped * 1.5), snapped * 1.5, 1e-9);
        assertTrue(snapped >= 15.0, "rounding a size down would overlap the next row");
    }

    @Test
    void rowBoundariesStayAlignedOverAThousandRows() {
        final double scale = 1.5;
        final double rowHeight = PixelSnap.snapSizeUp(16.0, scale);
        for (int row = 0; row < 1000; row++) {
            double device = PixelSnap.snap(row * rowHeight, scale) * scale;
            assertEquals(Math.round(device), device, 1e-9, "drifted by row " + row);
        }
    }

    @Test
    void aNonPositiveScaleIsAPassthroughRatherThanADivideByZero() {
        assertEquals(3.7, PixelSnap.snap(3.7, 0), 1e-9);
        assertEquals(3.7, PixelSnap.snapHairline(3.7, 0), 1e-9);
        assertEquals(1.0, PixelSnap.hairlineWidth(0), 1e-9);
        assertEquals(3.7, PixelSnap.snapSizeUp(3.7, -1), 1e-9);
    }
}
