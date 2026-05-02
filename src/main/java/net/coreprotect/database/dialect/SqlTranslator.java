package net.coreprotect.database.dialect;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lightweight SQL translation between CoreProtect's MySQL-flavoured runtime SQL
 * and PostgreSQL. Applied at the JDBC layer (see {@code PgConnectionProxy})
 * so SQL_QUERIES templates and dynamic SQL builders don't have to change.
 *
 * <p>Translations applied for Postgres:</p>
 * <ol>
 *   <li>{@code LIMIT n, m} → {@code LIMIT m OFFSET n} (handles literals and bind markers).</li>
 *   <li>The reserved column identifier {@code user} (bare, not {@code _user} / {@code username} / etc.)
 *       is quoted as {@code "user"}. String literals are protected from rewriting.</li>
 * </ol>
 */
public final class SqlTranslator {

    private SqlTranslator() {}

    private static final Pattern LIMIT_PAIR = Pattern.compile(
            "(?i)\\bLIMIT\\s+(\\d+|\\?|[A-Za-z_][A-Za-z0-9_]*)\\s*,\\s*(\\d+|\\?|[A-Za-z_][A-Za-z0-9_]*)\\b");

    private static final Pattern BARE_USER = Pattern.compile("\\buser\\b");

    /** ASCII unit-separator — illegal in well-formed SQL, used as our literal placeholder delimiter. */
    private static final char SEP = (char) 0x1F;

    public static String toPostgres(String sql) {
        if (sql == null || sql.isEmpty()) return sql;
        boolean fast = sql.indexOf('\'') < 0
                && sql.indexOf('"')  < 0
                && sql.indexOf("LIMIT") < 0 && sql.indexOf("limit") < 0
                && sql.indexOf("user") < 0 && sql.indexOf("USER") < 0;
        if (fast) return sql;

        // 1) Mask single-quoted string literals AND double-quoted identifiers
        //    (already-PG-quoted columns) so user/limit inside them doesn't match.
        StringBuilder masked = new StringBuilder(sql.length());
        List<String> literals = new ArrayList<>();
        char inQuote = 0; // 0 = no, '\'' = string, '"' = identifier
        StringBuilder cur = new StringBuilder();
        for (int i = 0; i < sql.length(); i++) {
            char c = sql.charAt(i);
            if (inQuote != 0) {
                cur.append(c);
                if (c == inQuote) {
                    if (i + 1 < sql.length() && sql.charAt(i + 1) == inQuote) {
                        // doubled quote = escaped
                        cur.append(c);
                        i++;
                    }
                    else {
                        inQuote = 0;
                        literals.add(cur.toString());
                        masked.append(SEP).append("L").append(literals.size() - 1).append(SEP);
                        cur.setLength(0);
                    }
                }
            }
            else {
                if (c == '\'' || c == '"') {
                    inQuote = c;
                    cur.append(c);
                }
                else {
                    masked.append(c);
                }
            }
        }
        if (inQuote != 0) {
            // Unterminated literal/identifier: bail.
            return sql;
        }

        String s = masked.toString();

        // 2) LIMIT n, m → LIMIT m OFFSET n
        Matcher m = LIMIT_PAIR.matcher(s);
        if (m.find()) {
            StringBuffer sb = new StringBuffer();
            do {
                sb.setLength(0);
                m.reset();
                while (m.find()) {
                    m.appendReplacement(sb, "LIMIT " + Matcher.quoteReplacement(m.group(2)) + " OFFSET " + Matcher.quoteReplacement(m.group(1)));
                }
                m.appendTail(sb);
                s = sb.toString();
                break;
            }
            while (false);
        }

        // 3) bare user → "user"
        Matcher mu = BARE_USER.matcher(s);
        if (mu.find()) {
            StringBuffer sb = new StringBuffer();
            mu.reset();
            while (mu.find()) {
                mu.appendReplacement(sb, "\"user\"");
            }
            mu.appendTail(sb);
            s = sb.toString();
        }

        // 4) Restore string literals.
        if (!literals.isEmpty()) {
            StringBuilder out = new StringBuilder(s.length());
            int i = 0;
            while (i < s.length()) {
                char c = s.charAt(i);
                if (c == SEP) {
                    int end = s.indexOf(SEP, i + 1);
                    if (end > i + 1) {
                        String marker = s.substring(i + 1, end); // L<n>
                        if (marker.length() > 1 && marker.charAt(0) == 'L') {
                            try {
                                int idx = Integer.parseInt(marker.substring(1));
                                if (idx >= 0 && idx < literals.size()) {
                                    out.append(literals.get(idx));
                                    i = end + 1;
                                    continue;
                                }
                            }
                            catch (NumberFormatException ignored) {}
                        }
                    }
                    out.append(c);
                    i++;
                }
                else {
                    out.append(c);
                    i++;
                }
            }
            s = out.toString();
        }

        return s;
    }
}
