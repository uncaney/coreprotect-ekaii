package net.coreprotect.utility;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parse human-friendly retention strings like {@code "30d"}, {@code "8w"},
 * {@code "6mo"}, {@code "1y2w"}. Returns the duration in seconds.
 *
 * <p>Compatible with the existing {@code /co purge t:&lt;time&gt;} syntax (which
 * accepts the same units): seconds (s), minutes (m), hours (h), days (d),
 * weeks (w), months (mo, 30d), years (y, 365d). The literal {@code "0"} or
 * {@code "off"} returns 0 — meaning "do not purge".</p>
 */
public final class HumanDuration {

    private static final Pattern TOKEN = Pattern.compile("(\\d+)\\s*(mo|y|w|d|h|m|s)", Pattern.CASE_INSENSITIVE);

    private HumanDuration() {}

    public static long parseSeconds(String input) {
        if (input == null) return 0;
        String s = input.trim().toLowerCase(Locale.ROOT);
        if (s.isEmpty() || s.equals("0") || s.equals("off") || s.equals("disabled") || s.equals("none")) {
            return 0;
        }
        // Pure number: assume seconds.
        if (s.matches("\\d+")) {
            try { return Long.parseLong(s); }
            catch (NumberFormatException e) { return 0; }
        }
        long total = 0;
        Matcher m = TOKEN.matcher(s);
        boolean any = false;
        while (m.find()) {
            any = true;
            long n;
            try { n = Long.parseLong(m.group(1)); }
            catch (NumberFormatException e) { return 0; }
            String unit = m.group(2).toLowerCase(Locale.ROOT);
            switch (unit) {
                case "s":  total += n;                    break;
                case "m":  total += n * 60L;              break;
                case "h":  total += n * 3600L;            break;
                case "d":  total += n * 86400L;           break;
                case "w":  total += n * 604800L;          break;
                case "mo": total += n * 2592000L;         break; // 30 days
                case "y":  total += n * 31536000L;        break; // 365 days
            }
        }
        return any ? total : 0;
    }
}
