package hacktrack.ai;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies how the HTTP fetcher and the headless-browser fallback interact for
 * the statuses a deployed instance actually sees: HTTP 403 from bot protection
 * must reach the browser fallback, everything else must keep the exact HTTP
 * behaviour and error mapping that already existed.
 */
class WebPageFetcherHttpFallbackTest {

    private static final String HTTP_403_MESSAGE = "The website returned an error (HTTP 403).";
    private static final String HTTP_404_MESSAGE = "That page was not found (404). Check the official URL.";
    private static final String NO_TEXT_MESSAGE = "The website did not return any readable text.";

    private HttpServer server;
    private String baseUrl;
    private AiProperties properties;
    private RecordingRenderedPageFetcher renderer;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/blocked", exchange -> respond(exchange, 403, "<html><body>Forbidden</body></html>"));
        server.createContext("/missing", exchange -> respond(exchange, 404, "<html><body>Not found</body></html>"));
        server.createContext("/spa", exchange ->
                respond(exchange, 200, "<html><body><div id=\"root\"></div></body></html>"));
        server.createContext("/static", exchange ->
                respond(exchange, 200, "<html><head><title>Timeline</title></head><body><p>"
                        + longText() + "</p></body></html>"));
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();

        properties = new AiProperties();
        renderer = new RecordingRenderedPageFetcher(properties);
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void http403_triggersTheHeadlessRenderFallback() {
        renderer.result = page(baseUrl + "/blocked", longText());
        WebPageFetcher fetcher = new WebPageFetcher(properties, renderer);

        List<FetchedPage> pages = fetcher.fetchSite(baseUrl + "/blocked");

        assertEquals(1, renderer.calls.size());
        assertEquals(URI.create(baseUrl + "/blocked"), renderer.calls.get(0));
        assertTrue(pages.get(0).getText().contains("Registration opens"));
    }

    @Test
    void http403_withoutAUsableBrowser_keepsTheOriginalHttpError() {
        renderer.result = null;
        WebPageFetcher fetcher = new WebPageFetcher(properties, renderer);

        AiScheduleException e = assertThrows(AiScheduleException.class,
                () -> fetcher.fetchSite(baseUrl + "/blocked"));

        assertEquals(AiScheduleException.Code.WEBSITE_UNAVAILABLE, e.getCode());
        assertEquals(HTTP_403_MESSAGE, e.getMessage());
        assertEquals(403, e.getSourceHttpStatus());
        assertEquals(1, renderer.calls.size());
    }

    @Test
    void http403_withOnlyABlockPage_keepsTheOriginalHttpError() {
        renderer.result = page(baseUrl + "/blocked", "Access denied by the CDN.");
        WebPageFetcher fetcher = new WebPageFetcher(properties, renderer);

        AiScheduleException e = assertThrows(AiScheduleException.class,
                () -> fetcher.fetchSite(baseUrl + "/blocked"));

        assertEquals(HTTP_403_MESSAGE, e.getMessage());
        assertEquals(403, e.getSourceHttpStatus());
    }

    @Test
    void http403_withFallbackDisabled_neverStartsABrowser() {
        properties.setRenderFallbackEnabled(false);
        renderer.result = page(baseUrl + "/blocked", longText());
        WebPageFetcher fetcher = new WebPageFetcher(properties, renderer);

        AiScheduleException e = assertThrows(AiScheduleException.class,
                () -> fetcher.fetchSite(baseUrl + "/blocked"));

        assertEquals(HTTP_403_MESSAGE, e.getMessage());
        assertTrue(renderer.calls.isEmpty(), "browser must not start when the fallback is disabled");
    }

    @Test
    void http404_isStillReportedAsNotFound_andNeverRenders() {
        renderer.result = page(baseUrl + "/missing", longText());
        WebPageFetcher fetcher = new WebPageFetcher(properties, renderer);

        AiScheduleException e = assertThrows(AiScheduleException.class,
                () -> fetcher.fetchSite(baseUrl + "/missing"));

        assertEquals(AiScheduleException.Code.WEBSITE_UNAVAILABLE, e.getCode());
        assertEquals(HTTP_404_MESSAGE, e.getMessage());
        assertTrue(renderer.calls.isEmpty(), "only HTTP 403 may trigger the browser");
    }

    @Test
    void networkFailure_keepsTheExistingMessage_andNeverRenders() {
        properties.setConnectTimeoutSeconds(1);
        properties.setRequestTimeoutSeconds(1);
        WebPageFetcher fetcher = new WebPageFetcher(properties, renderer);

        AiScheduleException e = assertThrows(AiScheduleException.class,
                () -> fetcher.fetchSite("http://127.0.0.1:1/"));

        assertEquals(AiScheduleException.Code.WEBSITE_UNAVAILABLE, e.getCode());
        assertEquals(0, e.getSourceHttpStatus());
        assertTrue(renderer.calls.isEmpty());
    }

    @Test
    void plainHttpSuccess_isUsedWithoutRendering() {
        WebPageFetcher fetcher = new WebPageFetcher(properties, renderer);

        List<FetchedPage> pages = fetcher.fetchSite(baseUrl + "/static");

        assertTrue(renderer.calls.isEmpty(), "a readable HTTP page must not start a browser");
        assertTrue(pages.get(0).getText().contains("Registration opens"));
        assertFalse(pages.get(0).getText().contains("<p>"));
    }

    @Test
    void clientSideRenderedPage_stillUsesTheFallbackAsBefore() {
        renderer.result = page(baseUrl + "/spa", longText());
        WebPageFetcher fetcher = new WebPageFetcher(properties, renderer);

        List<FetchedPage> pages = fetcher.fetchSite(baseUrl + "/spa");

        assertEquals(1, renderer.calls.size());
        assertTrue(pages.get(0).getText().contains("Registration opens"));
    }

    @Test
    void clientSideRenderedPage_withoutABrowser_reportsUnreadableText() {
        renderer.result = null;
        WebPageFetcher fetcher = new WebPageFetcher(properties, renderer);

        AiScheduleException e = assertThrows(AiScheduleException.class,
                () -> fetcher.fetchSite(baseUrl + "/spa"));

        assertEquals(AiScheduleException.Code.WEBSITE_UNAVAILABLE, e.getCode());
        assertEquals(NO_TEXT_MESSAGE, e.getMessage());
    }

    // ── Helpers ──

    private static String longText() {
        return "Registration opens on 20 October 2026. ".repeat(20);
    }

    private static FetchedPage page(String url, String text) {
        return new FetchedPage(url, "Rendered", text, List.of(), null);
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    /** Stands in for the real headless browser so no Chrome is needed in tests. */
    private static final class RecordingRenderedPageFetcher extends RenderedPageFetcher {
        private final List<URI> calls = new ArrayList<>();
        private FetchedPage result;

        private RecordingRenderedPageFetcher(AiProperties properties) {
            super(properties);
        }

        @Override
        public FetchedPage render(URI uri) {
            calls.add(uri);
            return result;
        }
    }
}
