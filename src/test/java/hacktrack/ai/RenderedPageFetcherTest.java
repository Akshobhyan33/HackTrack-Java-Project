package hacktrack.ai;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the parts of the headless-browser fallback that must hold in every
 * environment: the SSRF guard still runs before any browser starts, and the
 * Chrome arguments adapt to a container without changing local behaviour.
 *
 * <p>These tests deliberately never launch a browser: every case is decided
 * before ChromeDriverService would start.
 */
class RenderedPageFetcherTest {

    private final RenderedPageFetcher fetcher = new RenderedPageFetcher(new AiProperties());

    @Test
    void rejectsLoopbackAddressesBeforeStartingABrowser() {
        AiScheduleException e = assertThrows(AiScheduleException.class,
                () -> fetcher.render(URI.create("http://127.0.0.1:8080/admin")));

        assertEquals(AiScheduleException.Code.INVALID_URL, e.getCode());
    }

    @Test
    void rejectsCloudMetadataAddressesBeforeStartingABrowser() {
        AiScheduleException e = assertThrows(AiScheduleException.class,
                () -> fetcher.render(URI.create("http://169.254.169.254/latest/meta-data/")));

        assertEquals(AiScheduleException.Code.INVALID_URL, e.getCode());
    }

    @Test
    void rejectsNonHttpSchemesBeforeStartingABrowser() {
        AiScheduleException e = assertThrows(AiScheduleException.class,
                () -> fetcher.render(URI.create("ftp://example.com/timeline")));

        assertEquals(AiScheduleException.Code.INVALID_URL, e.getCode());
    }

    @Test
    void containerArguments_disableTheUnusableProcessSandbox() {
        List<String> args = RenderedPageFetcher.chromeArguments(true);

        assertTrue(args.contains("--no-sandbox"));
        assertTrue(args.contains("--disable-setuid-sandbox"));
        assertTrue(args.contains("--headless=new"));
        assertTrue(args.contains("--disable-dev-shm-usage"));
        assertTrue(args.contains("--window-size=1280,2400"));
    }

    @Test
    void hostArguments_keepTheProcessSandbox() {
        List<String> args = RenderedPageFetcher.chromeArguments(false);

        assertFalse(args.contains("--no-sandbox"));
        assertFalse(args.contains("--disable-setuid-sandbox"));
        assertTrue(args.contains("--headless=new"));
        assertTrue(args.contains("--disable-dev-shm-usage"));
    }

    @Test
    void noSandboxDecision_canBeOverriddenBySystemProperty() {
        try {
            System.setProperty("hacktrack.ai.chromeNoSandbox", "true");
            assertTrue(RenderedPageFetcher.needsNoSandbox());

            System.setProperty("hacktrack.ai.chromeNoSandbox", "false");
            assertFalse(RenderedPageFetcher.needsNoSandbox());
        } finally {
            System.clearProperty("hacktrack.ai.chromeNoSandbox");
        }

        assertEquals(RenderedPageFetcher.isContainerized(), RenderedPageFetcher.needsNoSandbox());
    }
}
