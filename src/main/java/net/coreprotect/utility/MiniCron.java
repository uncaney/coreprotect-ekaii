package net.coreprotect.utility;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

// Minimal 5-field cron parser (minute hour day month weekday, UTC).
// Supports literal numbers, ranges (0-30), step values (every-N), lists (0,15,30),
// wildcards (*), and day-of-week names mon-sun. Intentionally small.
public final class MiniCron {

    private static final String[] DOW = { "sun", "mon", "tue", "wed", "thu", "fri", "sat" };

    private final boolean[] minute;
    private final boolean[] hour;
    private final boolean[] dom;     // 1-31
    private final boolean[] month;   // 1-12
    private final boolean[] dow;     // 0-6 (Sun=0)

    public MiniCron(String expression) {
        String[] f = (expression == null ? "0 4 * * *" : expression.trim()).split("\\s+");
        if (f.length != 5) f = new String[] { "0", "4", "*", "*", "*" };
        this.minute = parse(f[0], 0, 59, false);
        this.hour   = parse(f[1], 0, 23, false);
        this.dom    = parse(f[2], 1, 31, false);
        this.month  = parse(f[3], 1, 12, false);
        this.dow    = parse(f[4], 0, 6,  true);
    }

    /** @return next firing instant strictly after {@code from}. */
    public Instant next(Instant from) {
        ZonedDateTime z = from.atZone(ZoneOffset.UTC).withSecond(0).withNano(0).plusMinutes(1);
        for (int i = 0; i < 366 * 24 * 60; i++) {
            int mo = z.getMonthValue();
            int d = z.getDayOfMonth();
            int dw = z.getDayOfWeek().getValue() % 7; // Java: Mon=1..Sun=7 → 1..0
            int h = z.getHour();
            int mi = z.getMinute();
            if (month[mo] && dom[d] && dow[dw] && hour[h] && minute[mi]) {
                return z.toInstant();
            }
            z = z.plusMinutes(1);
        }
        return from.plusSeconds(3600); // safety fallback
    }

    private static boolean[] parse(String spec, int min, int max, boolean dow) {
        boolean[] out = new boolean[max + 1];
        Arrays.fill(out, false);
        if (spec.equals("*")) {
            for (int i = min; i <= max; i++) out[i] = true;
            return out;
        }
        try {
            for (String token : spec.split(",")) {
                int step = 1;
                String range = token;
                int slash = token.indexOf('/');
                if (slash >= 0) {
                    step = Integer.parseInt(token.substring(slash + 1));
                    range = token.substring(0, slash);
                    if (range.isEmpty() || range.equals("*")) range = min + "-" + max;
                }
                int from, to;
                int dash = range.indexOf('-');
                if (dash >= 0) {
                    from = decode(range.substring(0, dash), dow);
                    to   = decode(range.substring(dash + 1), dow);
                }
                else if (range.equals("*")) {
                    from = min; to = max;
                }
                else {
                    from = to = decode(range, dow);
                }
                for (int v = from; v <= to; v += step) {
                    if (v >= min && v <= max) out[v] = true;
                }
            }
            return out;
        }
        catch (Exception e) {
            // bad expression → treat as wildcard so the service still runs.
            for (int i = min; i <= max; i++) out[i] = true;
            return out;
        }
    }

    private static int decode(String tok, boolean dow) {
        String t = tok.trim();
        if (dow) {
            for (int i = 0; i < DOW.length; i++) {
                if (t.equalsIgnoreCase(DOW[i])) return i;
            }
            // 7 also commonly used for Sunday.
            int n = Integer.parseInt(t);
            return n == 7 ? 0 : n;
        }
        return Integer.parseInt(t);
    }

    // Test convenience.
    public Set<Integer> activeMinutes() { return setOf(minute); }
    public Set<Integer> activeHours()   { return setOf(hour); }
    private static Set<Integer> setOf(boolean[] a) {
        Set<Integer> s = new HashSet<>();
        for (int i = 0; i < a.length; i++) if (a[i]) s.add(i);
        return s;
    }
}
