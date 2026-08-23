package io.github.tickgrid.demo.binance;

/**
 * A field scanner for the two Binance payloads this demo consumes. Not a JSON parser.
 *
 * <p>Bringing in Jackson to read {@code {"u":1,"s":"BTCUSDT","b":"77330.81"}} would add a dependency
 * and allocate a tree per message, on a path that runs thousands of times a second — which is the
 * one thing the rest of this project is careful not to do. The payloads are flat, the keys are
 * single characters, and the values are either quoted strings or bare numbers.
 *
 * <p>What that buys is the ability to hand a <i>slice</i> of the received text straight to
 * {@link io.github.tickgrid.render.FixedFormat#parseScaled}, so a price goes from wire to scaled
 * long with no intermediate {@code String} and no {@code double}.
 *
 * <p>What it costs is generality. These methods assume no nested objects, no escaped quotes and no
 * whitespace between tokens — all true of Binance's stream payloads, none of it true of JSON in
 * general. Point this at anything else and it will be wrong rather than slow.
 */
public final class Json {

    private Json() {
    }

    /** Index just past {@code "key":} for a multi-character key, or {@code -1}. */
    public static int member(CharSequence s, int from, int to, String key) {
        final int n = key.length();
        outer:
        for (int i = from; i + n + 3 <= to; i++) {
            if (s.charAt(i) != '"') continue;
            for (int j = 0; j < n; j++) {
                if (s.charAt(i + 1 + j) != key.charAt(j)) continue outer;
            }
            if (s.charAt(i + n + 1) == '"' && s.charAt(i + n + 2) == ':') {
                return i + n + 3;
            }
        }
        return -1;
    }

    /** Index just past {@code "key":} within {@code [from, to)}, or {@code -1}. */
    public static int field(CharSequence s, int from, int to, char key) {
        for (int i = from; i + 4 < to; i++) {
            if (s.charAt(i) == '"' && s.charAt(i + 1) == key
                    && s.charAt(i + 2) == '"' && s.charAt(i + 3) == ':') {
                return i + 4;
            }
        }
        return -1;
    }

    /** Start of a quoted value whose opening quote is at {@code pos}. */
    public static int stringStart(int pos) {
        return pos + 1;
    }

    /** Index of the closing quote of a string starting at {@code start}. */
    public static int stringEnd(CharSequence s, int start, int to) {
        for (int i = start; i < to; i++) {
            if (s.charAt(i) == '"') return i;
        }
        return to;
    }

    /** End of a bare numeric value beginning at {@code pos}. */
    public static int numberEnd(CharSequence s, int pos, int to) {
        int i = pos;
        while (i < to) {
            final char c = s.charAt(i);
            if ((c >= '0' && c <= '9') || c == '-' || c == '+' || c == '.' || c == 'e' || c == 'E') {
                i++;
            } else {
                break;
            }
        }
        return i;
    }

    /**
     * End of the flat object that opens at {@code start}, assuming no nested braces. Returns the
     * index just past the closing brace.
     */
    public static int flatObjectEnd(CharSequence s, int start, int to) {
        for (int i = start; i < to; i++) {
            if (s.charAt(i) == '}') return i + 1;
        }
        return to;
    }

    /** Index of the next {@code '{'} at or after {@code from}, or {@code -1}. */
    public static int nextObject(CharSequence s, int from, int to) {
        for (int i = from; i < to; i++) {
            if (s.charAt(i) == '{') return i;
        }
        return -1;
    }

    /** Whether {@code s} contains {@code needle} within {@code [from, to)}. */
    public static boolean contains(CharSequence s, int from, int to, String needle) {
        final int n = needle.length();
        outer:
        for (int i = from; i + n <= to; i++) {
            for (int j = 0; j < n; j++) {
                if (s.charAt(i + j) != needle.charAt(j)) continue outer;
            }
            return true;
        }
        return false;
    }
}
