package hacktrack.ai;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Single source of truth for the HackTrack stage lifecycle.
 *
 * HackTrack stores stage names as free text in the {@code stages} table, so this
 * catalog is what lets AI-extracted stages be mapped onto the existing stage
 * names (Registration → PPT Submission → PPT Result → Round 1 → Round 2 →
 * Final) instead of inventing a parallel stage system.
 */
public final class StageCatalog {

    public static final String REGISTRATION = "Registration";
    public static final String PPT_SUBMISSION = "PPT Submission";
    public static final String PPT_RESULT = "PPT Result";
    public static final String ROUND_1 = "Round 1";
    public static final String ROUND_2 = "Round 2";
    public static final String FINAL = "Final";

    /** Canonical names in lifecycle order. */
    public static final List<String> CANONICAL_ORDER = List.of(
            REGISTRATION, PPT_SUBMISSION, PPT_RESULT, ROUND_1, ROUND_2, FINAL);

    /** Prompt fragment telling the model which stage names already exist. */
    public static final String CANONICAL_PROMPT = String.join(", ", CANONICAL_ORDER);

    private record Rule(String canonical, Pattern pattern) {}

    /**
     * Ordered matching rules. More structurally specific wording is checked
     * first so that e.g. "Round 1 result" resolves to Round 1 rather than to the
     * generic result stage, and "Round 3" stays unmatched instead of being
     * forced onto an existing stage.
     */
    private static final List<Rule> RULES = buildRules();

    private StageCatalog() {
    }

    private static List<Rule> buildRules() {
        List<Rule> rules = new ArrayList<>();

        // 1. Explicit round and final wording
        rules.add(rule(ROUND_1, "\\b(round\\s*1|round\\s*i\\b|r\\s*1|1st\\s+round|first\\s+round|round\\s+one|screening\\s+round|prelim(?:inary)?\\s+round)\\b"));
        rules.add(rule(ROUND_2, "\\b(round\\s*2|round\\s*ii\\b|r\\s*2|2nd\\s+round|second\\s+round|round\\s+two)\\b"));
        rules.add(rule(FINAL, "\\b(final|finals|grand\\s*final|final\\s*round|finale|final\\s+show)\\b"));

        // 2. Result of an idea / proposal screening
        rules.add(rule(PPT_RESULT, "\\b(ppt|idea|proposal|abstract|synopsis|concept|project|screen(?:ing)?)\\b.*\\b(result|outcome|shortlist|selected|announce|status|cleared)\\b"));
        rules.add(rule(PPT_RESULT, "\\b(shortlist(?:ed)?|selected\\s+teams?|results?)\\b"));

        // 3. Registration / application
        rules.add(rule(REGISTRATION, "\\b(registr(?:ation|ation\\s+start|ation\\s+close|ering)|register|enrol?l?ment|sign\\s*up|apply|application)\\b"));

        // 4. Idea submission
        rules.add(rule(PPT_SUBMISSION, "\\b(submission|submit|submissions|submitting)\\b"));
        rules.add(rule(PPT_SUBMISSION, "\\b(ppt|idea|proposal|abstract|synopsis|concept)\\b"));

        return List.copyOf(rules);
    }

    private static Rule rule(String canonical, String regex) {
        return new Rule(canonical, Pattern.compile(regex, Pattern.CASE_INSENSITIVE));
    }

    /**
     * Resolves an arbitrary stage label onto a canonical HackTrack stage name.
     *
     * @param stageName    the label reported by the AI
     * @param description  optional supporting text, used only as a fallback
     * @return the canonical stage name, or null when nothing matches
     */
    public static String match(String stageName, String description) {
        if (stageName == null || stageName.isBlank()) {
            return null;
        }
        String normalized = normalize(stageName);

        for (String canonical : CANONICAL_ORDER) {
            if (normalized.equalsIgnoreCase(canonical)
                    || normalize(canonical).equalsIgnoreCase(normalized)
                    || normalized.startsWith(normalize(canonical).toLowerCase(Locale.ROOT) + " ")
                    || normalized.endsWith(" " + normalize(canonical).toLowerCase(Locale.ROOT))) {
                return canonical;
            }
        }

        for (Rule rule : RULES) {
            if (rule.pattern().matcher(stageName).find()) {
                return rule.canonical();
            }
        }

        if (description != null && !description.isBlank()) {
            for (Rule rule : RULES) {
                if (rule.pattern().matcher(description).find()) {
                    return rule.canonical();
                }
            }
        }
        return null;
    }

    /**
     * True when the given name is already one of the canonical HackTrack stages.
     */
    public static boolean isCanonical(String stageName) {
        if (stageName == null) {
            return false;
        }
        String normalized = normalize(stageName);
        for (String canonical : CANONICAL_ORDER) {
            if (normalized.equalsIgnoreCase(canonical)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Lifecycle position of a canonical stage (1-based), or null when the name is
     * not canonical.
     */
    public static Integer orderOf(String canonicalName) {
        int idx = CANONICAL_ORDER.indexOf(canonicalName);
        return idx < 0 ? null : idx + 1;
    }

    /**
     * Case/space/punctuation insensitive form used to compare stage names.
     */
    public static String normalize(String stageName) {
        if (stageName == null) {
            return "";
        }
        return stageName.replaceAll("[^a-z0-9]+", " ").trim().toLowerCase(Locale.ROOT);
    }

    /**
     * Groups existing database stages by canonical name so AI results can be
     * compared against what is already tracked.
     */
    public static Map<String, List<String>> indexByCanonicalName(Iterable<String> existingNames) {
        Map<String, List<String>> index = new LinkedHashMap<>();
        for (String name : existingNames) {
            String canonical = match(name, null);
            String key = canonical != null ? canonical : name;
            index.computeIfAbsent(key, k -> new ArrayList<>()).add(name);
        }
        return index;
    }
}
