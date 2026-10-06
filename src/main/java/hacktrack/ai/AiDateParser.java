package hacktrack.ai;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lenient but conservative date parsing for AI-extracted schedule entries.
 *
 * The model is asked to return ISO-8601 dates, but real websites contain dates
 * such as "20 October 2026", "Oct 20th, 2026" or ranges like "October 20-22".
 * Anything that cannot be resolved without guessing is reported as unknown so
 * the stage is skipped rather than saved with a fabricated deadline.
 */
public final class AiDateParser {

    private static final int MIN_YEAR = 2000;
    private static final int MAX_YEAR = 2100;

    private static final Pattern PLACEHOLDER = Pattern.compile(
            "(?i)^\\s*(tbd|tba|n/?a|na|none|null|unknown|to\\s+be\\s+announced|not\\s+(?:announced|specified|available)|"
                    + "pending|ongoing|date\\s+to\\s+be\\s+confirmed|[-–—\\.]+)\\s*$");

    private static final Pattern ISO = Pattern.compile("(?<y>\\d{4})\\s*[-/.]\\s*(?<m>\\d{1,2})\\s*[-/.]\\s*(?<d>\\d{1,2})");
    private static final Pattern COMPACT = Pattern.compile("(?<!\\d)(?<y>20\\d{2})(?<m>0[1-9]|1[0-2])(?<d>0[1-9]|[12]\\d|3[01])(?!\\d)");

    private static final Pattern MONTH_FIRST = Pattern.compile(
            "(?<m>jan|feb|mar|apr|may|jun|jul|aug|sep|sept|oct|nov|dec)[a-z]*\\.?,?\\s*(?<d>\\d{1,2})(?:st|nd|rd|th)?"
                    + "(?:\\s*,?\\s*(?<y>\\d{4}))?", Pattern.CASE_INSENSITIVE);
    private static final Pattern DAY_FIRST = Pattern.compile(
            "(?<d>\\d{1,2})(?:st|nd|rd|th)?\\s*(?:of\\s+)?(?<m>jan|feb|mar|apr|may|jun|jul|aug|sep|sept|oct|nov|dec)[a-z]*\\.?"
                    + "(?:\\s*,?\\s*(?<y>\\d{4}))?", Pattern.CASE_INSENSITIVE);

    /** "October 20-22, 2026" / "Oct 20 to 22 2026" - first day of the range wins. */
    private static final Pattern RANGE_MONTH_FIRST = Pattern.compile(
            "(?<m>jan|feb|mar|apr|may|jun|jul|aug|sep|sept|oct|nov|dec)[a-z]*\\.?,?\\s*(?<d>\\d{1,2})(?:st|nd|rd|th)?"
                    + "\\s*(?:-|–|—|\\bto\\b|\\bthrough\\b)\\s*\\d{1,2}(?:st|nd|rd|th)?"
                    + "(?:\\s*,?\\s*(?<y>\\d{4}))?", Pattern.CASE_INSENSITIVE);

    /** "20-22 October 2026" / "20 to 22 October 2026". */
    private static final Pattern RANGE_DAY_FIRST = Pattern.compile(
            "(?<d>\\d{1,2})(?:st|nd|rd|th)?\\s*(?:-|–|—|\\bto\\b|\\bthrough\\b)\\s*\\d{1,2}(?:st|nd|rd|th)?"
                    + "\\s*(?:of\\s+)?(?<m>jan|feb|mar|apr|may|jun|jul|aug|sep|sept|oct|nov|dec)[a-z]*\\.?"
                    + "(?:\\s*,?\\s*(?<y>\\d{4}))?", Pattern.CASE_INSENSITIVE);
    private static final Pattern NUMERIC = Pattern.compile(
            "(?<!\\d)(?<a>\\d{1,2})\\s*[-/.]\\s*(?<b>\\d{1,2})\\s*[-/.]\\s*(?<y>\\d{4})(?!\\d)");

    private static final String[] MONTHS = {
            "january", "february", "march", "april", "may", "june", "july",
            "august", "september", "october", "november", "december"
    };

    private static final DateTimeFormatter[] FORMATTERS = {
            DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.ENGLISH),
            DateTimeFormatter.ofPattern("yyyy/MM/dd", Locale.ENGLISH),
            DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.ENGLISH),
            DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH),
            DateTimeFormatter.ofPattern("MMMM d yyyy", Locale.ENGLISH),
            DateTimeFormatter.ofPattern("MMM d yyyy", Locale.ENGLISH),
            DateTimeFormatter.ofPattern("d/MM/yyyy", Locale.ENGLISH),
            DateTimeFormatter.ofPattern("dd-MM-yyyy", Locale.ENGLISH)
    };

    private AiDateParser() {
    }

    /**
     * Resolves a date or date range into a single day.
     *
     * For ranges such as "20-22 October 2026" or "2026-10-20 to 2026-10-22"
     * the first day is returned, which matches the deadline semantics used by
     * HackTrack stages.
     *
     * @return the resolved date, or null when it cannot be determined
     */
    public static LocalDate parse(String raw) {
        if (raw == null) {
            return null;
        }
        String value = raw.trim().toLowerCase(Locale.ROOT);
        if (value.isEmpty() || PLACEHOLDER.matcher(value).matches()) {
            return null;
        }
        LocalDate resolved = parseSingle(value);
        if (resolved != null) {
            return resolved;
        }
        // Ranges whose first half only makes sense on its own.
        for (String separator : new String[]{" to ", " upto ", " through "}) {
            int idx = value.indexOf(separator);
            if (idx > 0) {
                LocalDate first = parseSingle(value.substring(0, idx));
                if (first != null) {
                    return first;
                }
            }
        }
        return null;
    }

    private static LocalDate parseSingle(String value) {
        String cleaned = clean(value);
        if (cleaned.isEmpty()) {
            return null;
        }

        Matcher iso = ISO.matcher(cleaned);
        if (iso.find()) {
            LocalDate d = build(Integer.parseInt(iso.group("y")),
                    Integer.parseInt(iso.group("m")),
                    Integer.parseInt(iso.group("d")));
            if (d != null) {
                return d;
            }
        }

        LocalDate range = fromMonthName(RANGE_MONTH_FIRST, cleaned);
        if (range == null) {
            range = fromMonthName(RANGE_DAY_FIRST, cleaned);
        }
        if (range != null) {
            return range;
        }

        range = fromMonthName(MONTH_FIRST, cleaned);
        if (range == null) {
            range = fromMonthName(DAY_FIRST, cleaned);
        }
        if (range != null) {
            return range;
        }

        Matcher compact = COMPACT.matcher(cleaned);
        if (compact.find()) {
            LocalDate d = build(Integer.parseInt(compact.group("y")),
                    Integer.parseInt(compact.group("m")),
                    Integer.parseInt(compact.group("d")));
            if (d != null) {
                return d;
            }
        }

        Matcher numeric = NUMERIC.matcher(cleaned);
        if (numeric.find()) {
            int first = Integer.parseInt(numeric.group("a"));
            int second = Integer.parseInt(numeric.group("b"));
            int year = Integer.parseInt(numeric.group("y"));
            LocalDate asDayMonth = build(year, second, first);
            LocalDate asMonthDay = build(year, first, second);
            if (asDayMonth != null && asMonthDay != null) {
                // Genuinely ambiguous (e.g. 10/11/2026) - do not guess.
                return null;
            }
            if (asDayMonth != null) {
                return asDayMonth;
            }
            return asMonthDay;
        }

        for (DateTimeFormatter formatter : FORMATTERS) {
            try {
                return validate(LocalDate.parse(cleaned, formatter));
            } catch (DateTimeParseException ignored) {
                // try the next known layout
            }
        }
        return null;
    }

    /**
     * Applies a month-name based pattern. A year is mandatory: without one the
     * date is reported as unknown rather than assumed to be the current year.
     */
    private static LocalDate fromMonthName(Pattern pattern, String cleaned) {
        Matcher matcher = pattern.matcher(cleaned);
        while (matcher.find()) {
            String year = matcher.group("y");
            if (year == null) {
                continue;
            }
            LocalDate d = build(Integer.parseInt(year),
                    monthNumber(matcher.group("m")),
                    Integer.parseInt(matcher.group("d")));
            if (d != null) {
                return d;
            }
        }
        return null;
    }

    private static String clean(String value) {
        String s = value.toLowerCase(Locale.ROOT).trim();
        s = s.replaceAll("(?i)^\\s*(mon|tues|wednes|thurs|fri|satur|sun)[a-z]*day,?\\s*", "");
        s = s.replaceAll("(?i),?\\s*\\d{1,2}:\\d{2}\\s*(am|pm)?\\s*.*$", "");
        s = s.replaceAll("(?i)\\s*(ist|est|utc|gmt|cst)\\b.*$", "");
        s = s.replaceAll("\\s+", " ");
        return s.trim();
    }

    private static LocalDate build(int year, int month, int day) {
        if (month < 1 || month > 12 || day < 1 || day > 31) {
            return null;
        }
        try {
            return validate(LocalDate.of(year, month, day));
        } catch (java.time.DateTimeException e) {
            return null;
        }
    }

    private static LocalDate validate(LocalDate date) {
        if (date == null) {
            return null;
        }
        if (date.getYear() < MIN_YEAR || date.getYear() > MAX_YEAR) {
            return null;
        }
        return date;
    }

    private static int monthNumber(String token) {
        String key = token.toLowerCase(Locale.ROOT);
        if (key.startsWith("sept")) {
            key = "sep";
        }
        for (int i = 0; i < MONTHS.length; i++) {
            if (MONTHS[i].startsWith(key) || key.startsWith(MONTHS[i].substring(0, 3))) {
                return i + 1;
            }
        }
        return 0;
    }
}
