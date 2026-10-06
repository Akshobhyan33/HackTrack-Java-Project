package hacktrack.ai;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for the AI schedule extraction feature.
 *
 * The provider is the Google Gemini API. The API key is never stored in the
 * database, HTML or JavaScript. It is read from the {@code GEMINI_API_KEY}
 * environment variable (or an OS-level environment variable mapped through
 * Spring's relaxed binding) and kept in memory only.
 */
@ConfigurationProperties(prefix = "hacktrack.ai")
public class AiProperties {

    /** API key for the AI provider. Sourced from the GEMINI_API_KEY environment variable. */
    private String apiKey;

    /**
     * Gemini model used for extraction.
     *
     * <p>The default is a stable free-tier alias. Set {@code GEMINI_MODEL} to
     * {@code gemini-flash-latest} or a pinned version such as
     * {@code gemini-3.8-flash} if you have billing enabled and want a larger model.
     */
    private String model = "gemini-flash-lite-latest";

    /** Base URL of the Gemini API, without the model path. */
    private String baseUrl = "https://generativelanguage.googleapis.com/v1beta";

    /** Connect timeout for outgoing HTTP calls, in seconds. */
    private int connectTimeoutSeconds = 10;

    /** Total request timeout for outgoing HTTP calls, in seconds. */
    private int requestTimeoutSeconds = 60;

    /** Maximum number of characters of cleaned page text sent to the model. */
    private int maxContentChars = 24000;

    /** Maximum number of extra same-host pages followed looking for schedule data. */
    private int maxFollowLinks = 2;

    /** Hard cap on bytes downloaded from any single page. */
    private int maxPageBytes = 3000000;

    /**
     * Whether the headless-browser fallback may be used when plain HTTP
     * extraction returns too little text. Disable to keep extraction HTTP-only.
     */
    private boolean renderFallbackEnabled = true;

    /**
     * Minimum readable characters the HTTP path must produce before the
     * headless-browser fallback is considered at all.
     */
    private int minTextCharsForRenderFallback = 400;

    /** How long the headless browser may take to load and settle a page, in seconds. */
    private int renderTimeoutSeconds = 45;

    /** Extra wait after {@code document.readyState} completes, to let late scripts paint. */
    private int renderSettleMillis = 1500;

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public int getConnectTimeoutSeconds() {
        return connectTimeoutSeconds;
    }

    public void setConnectTimeoutSeconds(int connectTimeoutSeconds) {
        this.connectTimeoutSeconds = connectTimeoutSeconds;
    }

    public int getRequestTimeoutSeconds() {
        return requestTimeoutSeconds;
    }

    public void setRequestTimeoutSeconds(int requestTimeoutSeconds) {
        this.requestTimeoutSeconds = requestTimeoutSeconds;
    }

    public int getMaxContentChars() {
        return maxContentChars;
    }

    public void setMaxContentChars(int maxContentChars) {
        this.maxContentChars = maxContentChars;
    }

    public int getMaxFollowLinks() {
        return maxFollowLinks;
    }

    public void setMaxFollowLinks(int maxFollowLinks) {
        this.maxFollowLinks = maxFollowLinks;
    }

    public int getMaxPageBytes() {
        return maxPageBytes;
    }

    public void setMaxPageBytes(int maxPageBytes) {
        this.maxPageBytes = maxPageBytes;
    }

    public boolean isRenderFallbackEnabled() {
        return renderFallbackEnabled;
    }

    public void setRenderFallbackEnabled(boolean renderFallbackEnabled) {
        this.renderFallbackEnabled = renderFallbackEnabled;
    }

    public int getMinTextCharsForRenderFallback() {
        return minTextCharsForRenderFallback;
    }

    public void setMinTextCharsForRenderFallback(int minTextCharsForRenderFallback) {
        this.minTextCharsForRenderFallback = minTextCharsForRenderFallback;
    }

    public int getRenderTimeoutSeconds() {
        return renderTimeoutSeconds;
    }

    public void setRenderTimeoutSeconds(int renderTimeoutSeconds) {
        this.renderTimeoutSeconds = renderTimeoutSeconds;
    }

    public int getRenderSettleMillis() {
        return renderSettleMillis;
    }

    public void setRenderSettleMillis(int renderSettleMillis) {
        this.renderSettleMillis = renderSettleMillis;
    }

    /**
     * True when an API key has been supplied. The key itself is never returned
     * to the frontend.
     */
    public boolean isConfigured() {
        return apiKey != null && !apiKey.isBlank();
    }

    /**
     * Returns a short, non-sensitive description of the configured provider.
     */
    public String getProviderName() {
        return "Google Gemini";
    }
}
