package hacktrack.ai;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.Charset;
import java.nio.charset.IllegalCharsetNameException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Minimal server-side website fetcher used to give the AI readable text from a
 * hackathon's official page.
 *
 * This is deliberately not a crawler: it downloads the supplied URL, strips
 * scripts, styles and navigation, converts the HTML to plain text, and then
 * follows at most a couple of same-host links whose text or href looks like
 * schedule information.
 */
@Component
public class WebPageFetcher {

    private static final Pattern COMMENT = Pattern.compile("(?s)<!--.*?-->");
    private static final Pattern PAIRED_NOISE = Pattern.compile(
            "(?is)<(script|style|noscript|svg|canvas|template|iframe|nav|header|footer|aside|head|form)\\b[^>]*>.*?</\\1\\s*>");
    private static final Pattern UNCLOSED_NOISE = Pattern.compile(
            "(?is)<(script|style)\\b[^>]*>.*$");
    private static final Pattern TITLE = Pattern.compile("(?is)<title[^>]*>(.*?)</title\\s*>");
    private static final Pattern CHARSET_META = Pattern.compile(
            "(?is)<meta[^>]+charset\\s*=\\s*[\"']?\\s*([a-z0-9_\\-]+)");
    private static final Pattern BLOCK_BREAK = Pattern.compile(
            "(?i)</?(br|p|div|li|ul|ol|tr|table|h1|h2|h3|h4|h5|h6|section|article|blockquote|pre|hr)[^>]*>");
    private static final Pattern TAG = Pattern.compile("(?s)<[^>]*>");
    private static final Pattern ANCHOR = Pattern.compile(
            "(?is)<a\\b[^>]*href\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s>]+))[^>]*>(.*?)</a\\s*>");

    private static final String[] SCHEDULE_KEYWORDS = {
            "schedule", "timeline", "important date", "important-date", "important_date",
            "dates", "calendar", "agenda", "rules", "guideline", "registration",
            "register", "event", "process", "flow"
    };

    private final AiProperties properties;
    private final HttpClient httpClient;
    private final RenderedPageFetcher renderedPageFetcher;

    public WebPageFetcher(AiProperties properties, RenderedPageFetcher renderedPageFetcher) {
        this.properties = properties;
        this.renderedPageFetcher = renderedPageFetcher;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(Math.max(1, properties.getConnectTimeoutSeconds())))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    /**
     * Validates and normalises a user supplied URL, defaulting to https when no
     * scheme is present.
     *
     * @throws AiScheduleException when the URL is missing, malformed or not http(s)
     */
    public URI normalizeUrl(String rawUrl) {
        if (rawUrl == null || rawUrl.isBlank()) {
            throw new AiScheduleException(AiScheduleException.Code.INVALID_URL,
                    "No website URL was provided. Add the official hackathon URL and try again.");
        }
        String candidate = rawUrl.trim();
        if (!candidate.matches("(?i)^[a-z][a-z0-9+.\\-]*:.*")) {
            candidate = "https://" + candidate;
        }
        URI uri;
        try {
            uri = new URI(candidate);
        } catch (URISyntaxException e) {
            throw new AiScheduleException(AiScheduleException.Code.INVALID_URL,
                    "That does not look like a valid web address. Please check the URL and try again.");
        }
        String scheme = uri.getScheme();
        if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
            throw new AiScheduleException(AiScheduleException.Code.INVALID_URL,
                    "Only http and https website addresses can be analysed.");
        }
        if (uri.getHost() == null || uri.getHost().isBlank()) {
            throw new AiScheduleException(AiScheduleException.Code.INVALID_URL,
                    "That address is missing a website name. Please check the URL and try again.");
        }
        return uri;
    }

    /**
     * Fetches the supplied page plus a small number of same-host pages that look
     * like they contain schedule information.
     *
     * @return the cleaned pages, starting with the supplied URL
     */
    public List<FetchedPage> fetchSite(String rawUrl) {
        URI baseUri = normalizeUrl(rawUrl);
        FetchedPage base = fetchPage(baseUri);
        base = renderIfInsufficient(base);

        List<FetchedPage> pages = new ArrayList<>();
        pages.add(base);

        int budget = Math.max(0, properties.getMaxFollowLinks());
        if (budget == 0) {
            return pages;
        }

        Set<String> visited = new LinkedHashSet<>();
        visited.add(normalizeKey(base.getUrl()));

        for (String link : base.getRelevantLinks()) {
            if (budget == 0) {
                break;
            }
            String key = normalizeKey(link);
            if (!visited.add(key)) {
                continue;
            }
            try {
                FetchedPage page = fetchPage(new URI(link));
                if (!page.getText().isBlank()) {
                    pages.add(page);
                    budget--;
                }
            } catch (AiScheduleException e) {
                System.out.println("AI schedule: skipped related page " + link + " (" + e.getMessage() + ")");
            } catch (URISyntaxException e) {
                System.out.println("AI schedule: skipped malformed related link " + link);
            }
        }
        return pages;
    }

    /**
     * Falls back to a headless browser when the static HTML yielded too little
     * readable text, which is what a client-side rendered page looks like from
     * here.
     *
     * <p>Only the supplied page is rendered. The existing HTTP result is kept
     * whenever rendering fails or does not add text, so the original HTTP error
     * and error mapping are preserved exactly.
     */
    private FetchedPage renderIfInsufficient(FetchedPage page) {
        int threshold = Math.max(1, properties.getMinTextCharsForRenderFallback());
        if (RenderedPageFetcher.hasText(page.getText()) && page.getText().length() >= threshold) {
            return page;
        }
        if (!properties.isRenderFallbackEnabled()) {
            if (page.getText().isBlank()) {
                throw new AiScheduleException(AiScheduleException.Code.WEBSITE_UNAVAILABLE,
                        page.getNote() != null ? page.getNote()
                                : "The website did not return any readable text.");
            }
            return page;
        }

        // Reject loopback, private and metadata addresses before the browser runs.
        URI uri;
        try {
            uri = normalizeUrl(page.getUrl());
        } catch (AiScheduleException e) {
            return page;
        }

        FetchedPage rendered = renderedPageFetcher.render(uri);
        if (!isWorthUsing(rendered, page.getText(), threshold)) {
            // A browser that returned only site chrome, or less than the static text
            // already in hand, is not an improvement. Keep the HTTP result.
            if (page.getText().isBlank()) {
                throw new AiScheduleException(AiScheduleException.Code.WEBSITE_UNAVAILABLE,
                        page.getNote() != null ? page.getNote()
                                : "The website did not return any readable text.");
            }
            return page;
        }

        System.out.println("AI schedule: used headless rendering for " + uri
                + " (" + rendered.getText().length() + " readable characters).");
        return rendered;
    }

    /**
     * Decides whether the headless render should replace the static HTTP text.
     *
     * <p>The render has to be longer than what the HTTP path already produced,
     * otherwise it is only a worse view of the same page. When the HTTP path
     * produced nothing at all, the render also has to clear the same bar as a
     * normal successful extraction, so a page that renders to nothing but a
     * navigation bar is reported as unreadable rather than sent to the AI.
     */
    private static boolean isWorthUsing(FetchedPage rendered, String httpText, int threshold) {
        if (rendered == null || !RenderedPageFetcher.hasText(rendered.getText())) {
            return false;
        }
        int renderedLength = rendered.getText().length();
        int httpLength = httpText != null ? httpText.length() : 0;
        if (renderedLength <= httpLength) {
            return false;
        }
        return httpLength > 0 || RenderedPageFetcher.isSufficient(rendered.getText(), threshold);
    }

    /**
     * Downloads one page and converts it to readable text.
     */
    public FetchedPage fetchPage(URI uri) {
        HttpRequest request;
        try {
            request = HttpRequest.newBuilder(uri)
                    .GET()
                    .timeout(Duration.ofSeconds(Math.max(1, properties.getRequestTimeoutSeconds())))
                    .header("User-Agent", "Mozilla/5.0 (compatible; HackTrackAI/1.0; +schedule-extraction)")
                    .header("Accept", "text/html,application/xhtml+xml,text/plain;q=0.9,*/*;q=0.5")
                    .header("Accept-Language", "en-US,en;q=0.9")
                    .build();
        } catch (IllegalArgumentException e) {
            throw new AiScheduleException(AiScheduleException.Code.INVALID_URL,
                    "That website address cannot be requested.");
        }

        HttpResponse<InputStream> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (HttpTimeoutException e) {
            throw new AiScheduleException(AiScheduleException.Code.WEBSITE_UNAVAILABLE,
                    "The website took too long to respond. It may be offline — try again later.", e);
        } catch (IOException e) {
            throw new AiScheduleException(AiScheduleException.Code.WEBSITE_UNAVAILABLE,
                    "Could not reach that website. Check the URL and your internet connection.", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AiScheduleException(AiScheduleException.Code.WEBSITE_UNAVAILABLE,
                    "The website request was interrupted. Please try again.", e);
        }

        int status = response.statusCode();
        String contentType = response.headers().firstValue("content-type").orElse("").toLowerCase(Locale.ROOT);
        String finalUrl = response.uri() != null ? response.uri().toString() : uri.toString();

        if (status >= 400 || status == 0) {
            closeQuietly(response.body());
            throw new AiScheduleException(AiScheduleException.Code.WEBSITE_UNAVAILABLE,
                    status == 404
                            ? "That page was not found (404). Check the official URL."
                            : "The website returned an error (HTTP " + status + ").");
        }

        byte[] body;
        try (InputStream in = response.body()) {
            body = readLimited(in, properties.getMaxPageBytes());
        } catch (IOException e) {
            throw new AiScheduleException(AiScheduleException.Code.WEBSITE_UNAVAILABLE,
                    "The website response could not be read.", e);
        }

        boolean looksHtml = contentType.contains("html") || contentType.contains("xml")
                || contentType.contains("text/plain") || contentType.isEmpty();
        if (!looksHtml) {
            return new FetchedPage(finalUrl, null, "",
                    List.of(), "The website returned " + contentType + " instead of a readable web page.");
        }

        String raw = new String(body, detectCharset(contentType, body));
        if (raw.indexOf('\uFFFD') >= 0) {
            // Some older CMS pages declare UTF-8 but ship Windows-1252 bytes.
            String alt = new String(body, Charset.forName("windows-1252"));
            if (alt.indexOf('\uFFFD') < 0) {
                raw = alt;
            }
        }
        String title = extractTitle(raw);
        String text = htmlToText(raw);
        List<String> links = extractRelevantLinks(raw, finalUrl);
        return new FetchedPage(finalUrl, title, text, links, null);
    }

    // ── HTML cleanup ──

    /**
     * Converts an HTML document into readable plain text: comments, scripts,
     * styles, navigation and other chrome are removed, block level tags become
     * line breaks, remaining tags are dropped and entities are decoded.
     */
    static String htmlToText(String html) {
        if (html == null || html.isBlank()) {
            return "";
        }
        String s = COMMENT.matcher(html).replaceAll(" ");
        s = PAIRED_NOISE.matcher(s).replaceAll(" ");
        s = UNCLOSED_NOISE.matcher(s).replaceAll(" ");
        s = BLOCK_BREAK.matcher(s).replaceAll("\n");
        s = TAG.matcher(s).replaceAll(" ");
        s = decodeEntities(s);
        s = s.replace("\r\n", "\n").replace('\r', '\n');
        s = s.replace('\u00a0', ' ');

        StringBuilder out = new StringBuilder(s.length());
        String[] lines = s.split("\n", -1);
        int blankRun = 0;
        for (String line : lines) {
            String cleaned = line.replaceAll("[\\t\\x0B\\f ]+", " ").trim();
            if (cleaned.isEmpty()) {
                blankRun++;
                if (blankRun <= 1 && out.length() > 0) {
                    out.append('\n');
                }
                continue;
            }
            blankRun = 0;
            out.append(cleaned).append('\n');
        }
        return out.toString().trim();
    }

    static String decodeEntities(String input) {
        String s = input;
        s = s.replace("&nbsp;", " ").replace("&#160;", " ").replace("&ensp;", " ")
                .replace("&emsp;", " ").replace("&thinsp;", " ").replace("&shy;", "");
        s = s.replace("&mdash;", "-").replace("&ndash;", "-").replace("&minus;", "-")
                .replace("&middot;", "-").replace("&bull;", "-").replace("&hellip;", "...")
                .replace("&rsquo;", "'").replace("&lsquo;", "'").replace("&sbquo;", "'")
                .replace("&ldquo;", "\"").replace("&rdquo;", "\"").replace("&bdquo;", "\"")
                .replace("&amp;", "&").replace("&AMP;", "&")
                .replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'")
                .replace("&Tab;", "\t").replace("&NewLine;", "\n");

        StringBuilder out = new StringBuilder(s.length());
        int i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == '&' && i + 2 < s.length() && s.charAt(i + 1) == '#') {
                int j = i + 2;
                boolean hex = j < s.length() && (s.charAt(j) == 'x' || s.charAt(j) == 'X');
                if (hex) {
                    j++;
                }
                int start = j;
                while (j < s.length() && Character.digit(s.charAt(j), hex ? 16 : 10) >= 0) {
                    j++;
                }
                if (j > start && j < s.length() && s.charAt(j) == ';') {
                    try {
                        int codePoint = Integer.parseInt(s.substring(start, j), hex ? 16 : 10);
                        if (Character.isValidCodePoint(codePoint)) {
                            out.appendCodePoint(codePoint);
                            i = j + 1;
                            continue;
                        }
                    } catch (NumberFormatException ignored) {
                        // fall through and keep the raw text
                    }
                }
            }
            out.append(c);
            i++;
        }
        return out.toString();
    }

    private static String extractTitle(String html) {
        Matcher m = TITLE.matcher(html);
        if (m.find()) {
            String title = decodeEntities(TAG.matcher(m.group(1)).replaceAll(" ")).trim();
            if (!title.isEmpty()) {
                return title.length() > 200 ? title.substring(0, 200) : title;
            }
        }
        return null;
    }

    /**
     * Finds same-host links whose href or anchor text looks schedule related.
     * Fragments on the current page and non-HTML downloads are skipped, because
     * fetching them would waste a request.
     */
    static List<String> extractRelevantLinks(String html, String baseUrl) {
        URI base;
        String baseHost;
        try {
            base = new URI(baseUrl);
            baseHost = base.getHost();
        } catch (URISyntaxException e) {
            return List.of();
        }
        if (baseHost == null) {
            return List.of();
        }
        String baseKey = normalizeKey(baseUrl);

        Set<String> result = new LinkedHashSet<>();
        Matcher m = ANCHOR.matcher(html);
        while (m.find()) {
            String href = firstNonNull(m.group(1), m.group(2), m.group(3));
            if (href == null || href.startsWith("#") || href.startsWith("javascript:") || href.startsWith("mailto:")) {
                continue;
            }
            String anchorText = decodeEntities(TAG.matcher(m.group(4)).replaceAll(" ")).trim();
            String haystack = (href + " " + anchorText).toLowerCase(Locale.ROOT);
            boolean relevant = Arrays.stream(SCHEDULE_KEYWORDS).anyMatch(haystack::contains);
            if (!relevant) {
                continue;
            }
            String absolute = resolve(base, href);
            if (absolute == null) {
                continue;
            }
            try {
                URI uri = new URI(absolute);
                String scheme = uri.getScheme();
                if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
                    continue;
                }
                if (!sameSite(uri.getHost(), baseHost)) {
                    continue;
                }
                if (isDownload(uri.getPath())) {
                    continue;
                }
                String cleaned = stripFragment(uri);
                if (normalizeKey(cleaned).equals(baseKey)) {
                    continue;
                }
                result.add(cleaned);
            } catch (URISyntaxException e) {
                continue;
            }
        }

        List<String> links = new ArrayList<>(result);
        return links.size() > 8 ? links.subList(0, 8) : links;
    }

    private static String stripFragment(URI uri) {
        String value = uri.toString();
        int idx = value.indexOf('#');
        return idx >= 0 ? value.substring(0, idx) : value;
    }

    private static boolean isDownload(String path) {
        if (path == null) {
            return false;
        }
        String lower = path.toLowerCase(Locale.ROOT);
        for (String ext : new String[]{".pdf", ".zip", ".png", ".jpg", ".jpeg", ".gif", ".svg",
                ".doc", ".docx", ".xls", ".xlsx", ".ppt", ".pptx", ".csv", ".mp4", ".exe", ".rar", ".7z"}) {
            if (lower.endsWith(ext)) {
                return true;
            }
        }
        return false;
    }

    private static String firstNonNull(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v.trim();
            }
        }
        return null;
    }

    private static boolean sameSite(String hostA, String hostB) {
        if (hostA == null || hostB == null) {
            return false;
        }
        return stripWww(hostA).equalsIgnoreCase(stripWww(hostB));
    }

    private static String stripWww(String host) {
        return host.toLowerCase(Locale.ROOT).startsWith("www.") ? host.substring(4) : host;
    }

    private static String resolve(URI base, String href) {
        try {
            URI resolved = base.resolve(href.trim());
            return resolved.isAbsolute() ? resolved.toString() : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String normalizeKey(String url) {
        String key = url == null ? "" : url;
        int idx = key.indexOf('#');
        if (idx >= 0) {
            key = key.substring(0, idx);
        }
        return key.endsWith("/") ? key.substring(0, key.length() - 1) : key;
    }

    private static Charset detectCharset(String contentType, byte[] body) {
        Matcher m = Pattern.compile("charset\\s*=\\s*\"?\\s*([a-z0-9_\\-]+)", Pattern.CASE_INSENSITIVE)
                .matcher(contentType);
        if (m.find()) {
            Charset c = safeCharset(m.group(1));
            if (c != null) {
                return c;
            }
        }
        String head = new String(body, 0, Math.min(body.length, 2048), StandardCharsets.ISO_8859_1);
        Matcher meta = CHARSET_META.matcher(head);
        if (meta.find()) {
            Charset c = safeCharset(meta.group(1));
            if (c != null) {
                return c;
            }
        }
        return StandardCharsets.UTF_8;
    }

    private static Charset safeCharset(String name) {
        try {
            return Charset.forName(name.trim());
        } catch (IllegalCharsetNameException | java.nio.charset.UnsupportedCharsetException e) {
            return null;
        }
    }

    private static byte[] readLimited(InputStream in, int maxBytes) throws IOException {
        int limit = Math.max(1024, maxBytes);
        byte[] buffer = new byte[8192];
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        int total = 0;
        int read;
        while (total < limit && (read = in.read(buffer)) != -1) {
            out.write(buffer, 0, read);
            total += read;
        }
        return out.toByteArray();
    }

    private static void closeQuietly(InputStream in) {
        try {
            if (in != null) {
                in.close();
            }
        } catch (IOException ignored) {
            // nothing useful to do here
        }
    }
}
