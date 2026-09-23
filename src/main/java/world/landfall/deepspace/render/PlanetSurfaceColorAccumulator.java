package world.landfall.deepspace.render;

/**
 * Computes an alpha-weighted sRGB average from NativeImage ABGR pixels.
 */
public final class PlanetSurfaceColorAccumulator {
    private long redTotal;
    private long greenTotal;
    private long blueTotal;
    private long alphaTotal;

    public void addAbgr(int abgr) {
        int alpha = abgr >>> 24;
        if (alpha == 0) {
            return;
        }
        redTotal += (abgr & 0xFFL) * alpha;
        greenTotal += ((abgr >>> 8) & 0xFFL) * alpha;
        blueTotal += ((abgr >>> 16) & 0xFFL) * alpha;
        alphaTotal += alpha;
    }

    public int toOpaqueArgb(int fallbackArgb) {
        if (alphaTotal == 0L) {
            return fallbackArgb | 0xFF000000;
        }
        int red = roundedChannel(redTotal);
        int green = roundedChannel(greenTotal);
        int blue = roundedChannel(blueTotal);
        return 0xFF000000 | red << 16 | green << 8 | blue;
    }

    private int roundedChannel(long total) {
        return (int) ((total + alphaTotal / 2L) / alphaTotal);
    }
}
