package hacktrack.ai;

import org.openqa.selenium.Dimension;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.PageLoadStrategy;
import org.openqa.selenium.TimeoutException;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeDriverService;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.support.ui.WebDriverWait;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Renders a page in headless Chrome and returns its visible text.
 *
 * <p>Used only as a fallback when the plain HTTP fetcher cannot find readable
 * text, which is what a client-side rendered page looks like from here: the HTML
 * ships an empty mount point such as {@code <div id="root"></div>} and the real
 * content only appears once JavaScript has run.
 *
 * <p>This is deliberately not a crawler. It opens the single supplied URL, waits
 * for the page to settle, reads the rendered text once and quits. {@link UrlSafety}
 * is applied first so the browser cannot be pointed at loopback, private or cloud
 * metadata addresses.
 */
@Component
public class RenderedPageFetcher {

    /** Elements that never contribute readable page text. */
    private static final String HIDE_SCRIPT =
            "var s=document.createElement('style');"
                    + "s.textContent='script,style,noscript,template,svg,canvas,[hidden],[aria-hidden=\"true\"]"
                    + "{display:none!important}';"
                    + "(document.head||document.documentElement).appendChild(s);";

    private final AiProperties properties;

    public RenderedPageFetcher(AiProperties properties) {
        this.properties = properties;
    }

    /**
     * Loads the URL in headless Chrome and extracts readable text from the
     * rendered DOM.
     *
     * <p>Never throws for an unavailable browser, a blocked driver download or a
     * page that fails to load: those return {@code null} so the caller keeps the
     * original HTTP text or error instead of replacing it with a browser-specific
     * failure.
     *
     * @return the rendered page, or {@code null} when rendering was not possible
     * @throws AiScheduleException {@code INVALID_URL} when the URL is not a public
     *                             http(s) address
     */
    public FetchedPage render(URI uri) {
        UrlSafety.requirePublicHttpUrl(uri);

        ChromeDriver driver = null;
        ChromeDriverService service = null;
        try {
            service = new ChromeDriverService.Builder().build();

            ChromeOptions options = new ChromeOptions();
            options.setPageLoadStrategy(PageLoadStrategy.NORMAL);
            options.addArguments(chromeArguments(needsNoSandbox()));

            // The Docker image pins the browser and driver locations; local
            // development has neither variable set and keeps automatic discovery.
            String chromeBinary = existingFile(System.getenv("CHROME_BIN"));
            if (chromeBinary != null) {
                options.setBinary(chromeBinary);
            }
            String driverPath = existingFile(System.getenv("CHROMEDRIVER_PATH"));
            if (driverPath != null && System.getProperty("webdriver.chrome.driver") == null) {
                System.setProperty("webdriver.chrome.driver", driverPath);
            }

            driver = new ChromeDriver(service, options);
            Duration budget = Duration.ofSeconds(Math.max(5, properties.getRenderTimeoutSeconds()));
            driver.manage().timeouts().pageLoadTimeout(budget);
            driver.manage().window().setSize(new Dimension(1280, 2400));

            driver.get(uri.toString());
            waitForRender(driver, budget);

            // Hide non-content nodes before reading, so innerText returns only text
            // a visitor can actually see.
            execute(driver, HIDE_SCRIPT);

            String title = driver.getTitle();
            String finalUrl = driver.getCurrentUrl();
            String html = driver.getPageSource();
            String text = visibleText(driver);

            return new FetchedPage(
                    finalUrl != null && !finalUrl.isBlank() ? finalUrl : uri.toString(),
                    title != null && !title.isBlank() ? title : null,
                    text,
                    WebPageFetcher.extractRelevantLinks(html,
                            finalUrl != null && !finalUrl.isBlank() ? finalUrl : uri.toString()),
                    null);
        } catch (AiScheduleException e) {
            throw e;
        } catch (Exception e) {
            // Chrome missing, driver download blocked, page crash or timeout: all of
            // these must leave the existing HTTP behaviour and error mapping intact.
            System.out.println("AI schedule: headless rendering was unavailable for this page ("
                    + e.getClass().getSimpleName() + "), keeping the HTTP result.");
            if (System.getProperty("hacktrack.ai.renderDebug") != null) {
                e.printStackTrace();
            }
            return null;
        } finally {
            if (driver != null) {
                try {
                    driver.quit();
                } catch (RuntimeException ignored) {
                    // The browser is being discarded; nothing useful to do.
                }
            }
            if (service != null) {
                try {
                    service.stop();
                } catch (RuntimeException ignored) {
                    // Same.
                }
            }
        }
    }

    /**
     * Waits for the page to finish loading, then for late scripts to populate it.
     *
     * <p>A fixed sleep is not enough here. On a repeat navigation the browser can
     * report {@code document.readyState == "complete"} within milliseconds while
     * the framework has only painted its navigation bar, filling the page in a
     * second later. So this waits a minimum settle window from the start of the
     * load and only then accepts the text once two consecutive readings agree,
     * with the render budget as the hard ceiling.
     */
    private void waitForRender(WebDriver driver, Duration budget) {
        long start = System.nanoTime();
        long budgetNanos = budget.toNanos();

        try {
            new WebDriverWait(driver, budget, Duration.ofMillis(300))
                    .until(d -> "complete".equals(jsString(d, "return document.readyState;")));
        } catch (TimeoutException ignored) {
            // Slow third-party assets; read whatever has rendered so far.
        }

        long minimumWaitNanos = Math.max(0, properties.getRenderSettleMillis()) * 1_000_000L;
        long previous = -1;
        int stableReads = 0;
        while (true) {
            long elapsed = System.nanoTime() - start;
            long current = bodyTextLength(driver);
            if (current == previous && current > 0) {
                stableReads++;
            } else {
                stableReads = 0;
            }
            previous = current;

            boolean pastMinimum = elapsed >= minimumWaitNanos;
            boolean settled = pastMinimum && stableReads >= 2;
            if (settled || elapsed >= budgetNanos) {
                return;
            }
            sleep(250);
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static long bodyTextLength(WebDriver driver) {
        Object length = execute(driver,
                "return (document.body && document.body.innerText || '').trim().length;");
        return length instanceof Number number ? number.longValue() : 0L;
    }

    /**
     * Reads the text a visitor can actually see, then runs it through the same
     * static cleanup the HTTP path uses so the AI receives identical formatting.
     */
    private String visibleText(WebDriver driver) {
        String rendered = jsString(driver, "return document.body ? document.body.innerText : '';");
        if (rendered == null || rendered.isBlank()) {
            rendered = jsString(driver, "return document.documentElement.innerText || '';");
        }
        if (rendered == null || rendered.isBlank()) {
            return "";
        }
        return WebPageFetcher.htmlToText("<pre>" + rendered.replace("&", "&amp;") + "</pre>");
    }

    private static Object execute(WebDriver driver, String script, Object... args) {
        return ((JavascriptExecutor) driver).executeScript(script, args);
    }

    private static String jsString(WebDriver driver, String script, Object... args) {
        Object result = execute(driver, script, args);
        return result instanceof String value ? value : null;
    }

    // ── Container support ──

    /**
     * Command line passed to headless Chrome.
     *
     * @param noSandbox true when Chrome must run without its process sandbox
     */
    static List<String> chromeArguments(boolean noSandbox) {
        List<String> args = new ArrayList<>(List.of(
                "--headless=new",
                "--disable-gpu",
                "--disable-dev-shm-usage",
                "--disable-extensions",
                "--disable-background-networking",
                "--disable-background-timer-throttling",
                "--disable-renderer-backgrounding",
                "--mute-audio",
                "--window-size=1280,2400",
                "--user-agent=Mozilla/5.0 (compatible; HackTrackScheduleBot/1.0)",
                "--lang=en-US"));
        if (noSandbox) {
            // Inside a container the setuid sandbox helper is usually unusable
            // (the runtime commonly enables no-new-privileges), and Chrome exits
            // instead of starting. The SSRF protection is unaffected: UrlSafety
            // still decides which URL the browser may open at all.
            args.add("--no-sandbox");
            args.add("--disable-setuid-sandbox");
        }
        return args;
    }

    /**
     * True when Chrome must start without its process sandbox.
     *
     * <p>Overridable with {@code -Dhacktrack.ai.chromeNoSandbox=true|false};
     * otherwise containerisation is detected from {@code /.dockerenv} and
     * {@code /proc/1/cgroup}, so plain local development keeps the sandbox.
     */
    static boolean needsNoSandbox() {
        String override = System.getProperty("hacktrack.ai.chromeNoSandbox");
        if (override != null && !override.isBlank()) {
            return Boolean.parseBoolean(override);
        }
        return isContainerized();
    }

    /** True when running inside a Docker/container runtime. */
    static boolean isContainerized() {
        if (Files.exists(Path.of("/.dockerenv"))) {
            return true;
        }
        try {
            String cgroup = Files.readString(Path.of("/proc/1/cgroup"));
            return cgroup.contains("docker") || cgroup.contains("containerd")
                    || cgroup.contains("kubepods") || cgroup.contains("lxc");
        } catch (IOException | UnsupportedOperationException | SecurityException e) {
            // Not Linux, or not readable: behave like a normal host.
            return false;
        }
    }

    /** Returns the path when it is set and points at an existing file. */
    private static String existingFile(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Files.exists(Path.of(value)) ? value : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** True when a rendered page produced at least this many characters of text. */
    static boolean isSufficient(String text, int minChars) {
        return hasText(text) && text.length() >= Math.max(1, minChars);
    }

    static boolean hasText(String text) {
        return text != null && !text.isBlank();
    }

    /** Exposed for callers that need the visible-text extraction without a browser. */
    static List<String> noLinks() {
        return List.of();
    }
}
