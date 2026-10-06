package hacktrack.ai;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

/**
 * Sends the cleaned website text to Google Gemini and parses the structured
 * JSON schedule it returns.
 *
 * <p>The API key only ever lives in the {@link AiProperties} bean, is attached to
 * the outgoing request header and is never logged or returned to a client.
 */
@Component
public class AiScheduleExtractor {

    private static final String SYSTEM_PROMPT = """
            You extract hackathon competition schedules from official website text.

            Rules:
            1. Only report dates that are explicitly written in the supplied content. Never estimate,
               infer, remember or invent a date, and never use the current date.
            2. If a stage is mentioned but its date is missing or unclear, still report the stage but
               set "date" to null and explain the situation in "description".
            3. If the supplied content contains no identifiable stages or deadlines at all, return an
               empty "stages" array.
            4. Convert every date to ISO-8601 "YYYY-MM-DD":
               - "20 October 2026"          -> "2026-10-20"
               - "Oct 20th, 2026"           -> "2026-10-20"
               - "20/10/2026"               -> "2026-10-20"
               - "2026.10.20"               -> "2026-10-20"
               - "October 20-22, 2026"      -> "2026-10-20" and mention the full range in "description"
               - "20 Oct 2026, 5:00 PM IST" -> "2026-10-20"
               A year is required. If the year is not stated, set "date" to null.
            5. For deadline style items use the closing date; for multi-day events use the first day.
            6. Reuse the existing HackTrack stage names where they fit: %s
               If a stage clearly does not fit one of them (for example "Round 3" or "Grand Challenge"),
               still report it but use the website's own wording as "stageName".
            7. Keep "description" under 120 characters and preserve the website's original wording.
            8. Report at most 12 stages, in chronological order.

            Reply with a single JSON object and nothing else, in exactly this shape:
            {"hackathonName": "...", "stages": [{"stageName": "...", "date": "YYYY-MM-DD or null", "description": "..."}]}
            """;

    private final AiProperties properties;
    private final WebPageFetcher pageFetcher;
    private final HttpClient httpClient;

    public AiScheduleExtractor(AiProperties properties, WebPageFetcher pageFetcher) {
        this.properties = properties;
        this.pageFetcher = pageFetcher;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(Math.max(1, properties.getConnectTimeoutSeconds())))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    /**
     * True when an API key is available. Used by the status endpoint so the UI
     * can explain what to configure without ever revealing the key.
     */
    public boolean isConfigured() {
        return properties.isConfigured();
    }

    /**
     * Fetches the hackathon website and asks the AI to extract its schedule.
     *
     * @param hackathonName name of the hackathon (may be blank)
     * @param rawUrl        official hackathon URL
     * @return the extracted schedule, mapped onto existing HackTrack stage names
     */
    public ExtractedSchedule extract(String hackathonName, String rawUrl) {
        URI uri = pageFetcher.normalizeUrl(rawUrl);

        if (!properties.isConfigured()) {
            throw new AiScheduleException(AiScheduleException.Code.AI_KEY_MISSING,
                    "AI extraction is not configured. Set the GEMINI_API_KEY environment variable and restart HackTrack.");
        }

        List<FetchedPage> pages = pageFetcher.fetchSite(rawUrl);

        StringBuilder content = new StringBuilder();
        int budget = Math.max(1000, properties.getMaxContentChars());
        for (FetchedPage page : pages) {
            String block = "\n=== PAGE: " + page.getUrl()
                    + (page.getTitle() != null ? " | title: " + page.getTitle() : "") + " ===\n"
                    + page.getText() + "\n";
            if (content.length() + block.length() > budget) {
                content.append(block, 0, Math.max(0, budget - content.length()));
                break;
            }
            content.append(block);
        }
        if (content.toString().isBlank()) {
            throw new AiScheduleException(AiScheduleException.Code.NO_SCHEDULE_FOUND,
                    "No readable text could be extracted from that website.");
        }

        String userPrompt = "Hackathon name: " + (hackathonName == null || hackathonName.isBlank() ? "(unknown)" : hackathonName)
                + "\nOfficial URL: " + uri
                + "\n\nWebsite content:\n" + content;

        String responseJson = callModel(userPrompt);

        ExtractedSchedule schedule = parseResponse(responseJson);
        schedule.setHackathonName(schedule.getHackathonName() != null && !schedule.getHackathonName().isBlank()
                ? schedule.getHackathonName()
                : (hackathonName == null || hackathonName.isBlank() ? uri.getHost() : hackathonName));
        schedule.setWebsiteUrl(uri.toString());
        for (FetchedPage page : pages) {
            schedule.getPagesRead().add(page.getUrl());
            if (page.getNote() != null) {
                schedule.getNotes().add(page.getNote());
            }
        }
        return schedule;
    }

    // ── Provider call ──

    private String callModel(String userPrompt) {
        String endpoint = trimTrailingSlash(properties.getBaseUrl())
                + "/models/" + properties.getModel().trim() + ":generateContent";
        String body = buildRequestBody(userPrompt);

        HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint))
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .timeout(Duration.ofSeconds(Math.max(1, properties.getRequestTimeoutSeconds())))
                .header("Content-Type", "application/json")
                .header("x-goog-api-key", properties.getApiKey())
                .build();

        HttpResponse<String> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (HttpTimeoutException e) {
            throw new AiScheduleException(AiScheduleException.Code.AI_TIMEOUT,
                    "The AI service took too long to respond. Please try again.", e);
        } catch (IOException e) {
            throw new AiScheduleException(AiScheduleException.Code.AI_UNAVAILABLE,
                    "Could not reach the AI service. Check your internet connection and try again.", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AiScheduleException(AiScheduleException.Code.AI_UNAVAILABLE,
                    "The AI request was interrupted. Please try again.", e);
        }

        int status = response.statusCode();
        if (status == 401 || status == 403) {
            System.err.println("AI schedule extraction rejected (HTTP " + status + "). Check the GEMINI_API_KEY value.");
            throw new AiScheduleException(AiScheduleException.Code.AI_KEY_INVALID,
                    "The AI API key was rejected. Check the GEMINI_API_KEY environment variable and restart HackTrack.");
        }
        if (status == 429) {
            throw new AiScheduleException(AiScheduleException.Code.AI_RATE_LIMIT,
                    "The AI service is rate limiting requests. Wait a moment and try again.");
        }
        if (status >= 400) {
            System.err.println("AI schedule extraction failed with HTTP " + status
                    + " (" + providerErrorType(response.body()) + ").");
            throw new AiScheduleException(AiScheduleException.Code.AI_UNAVAILABLE,
                    status == 404
                            ? "The configured AI model was not found. Check the GEMINI_MODEL setting."
                            : "The AI service returned an error (HTTP " + status + "). Please try again.");
        }

        String content = readContent(response.body());
        if (content == null || content.isBlank()) {
            throw new AiScheduleException(AiScheduleException.Code.AI_MALFORMED_RESPONSE,
                    "The AI service returned an empty response. Please try again.");
        }
        return content;
    }

    /**
     * Builds a Gemini {@code generateContent} request.
     *
     * <p>The extraction instructions move to {@code systemInstruction}, the website
     * text becomes a single user content part, and {@code generationConfig} asks for
     * structured output so the reply already matches the HackTrack schedule contract.
     * No sampling parameters are sent: Gemini 3.5 and later reject {@code temperature}.
     */
    private String buildRequestBody(String userPrompt) {
        JsonObject root = new JsonObject();

        JsonObject systemInstruction = new JsonObject();
        JsonArray systemParts = new JsonArray();
        systemParts.add(textPart(SYSTEM_PROMPT.formatted(StageCatalog.CANONICAL_PROMPT)));
        systemInstruction.add("parts", systemParts);
        root.add("systemInstruction", systemInstruction);

        JsonObject userContent = new JsonObject();
        userContent.addProperty("role", "user");
        JsonArray userParts = new JsonArray();
        userParts.add(textPart(userPrompt));
        userContent.add("parts", userParts);

        JsonArray contents = new JsonArray();
        contents.add(userContent);
        root.add("contents", contents);

        JsonObject generationConfig = new JsonObject();
        generationConfig.addProperty("responseMimeType", "application/json");
        generationConfig.add("responseSchema", responseSchema());
        root.add("generationConfig", generationConfig);

        return root.toString();
    }

    private static JsonObject textPart(String text) {
        JsonObject part = new JsonObject();
        part.addProperty("text", text);
        return part;
    }

    /**
     * Schema for the existing HackTrack extraction contract.
     *
     * <p>{@code date} is nullable because a stage may be named without a usable
     * date, and the model must be allowed to report that honestly instead of
     * inventing one.
     */
    private static JsonObject responseSchema() {
        JsonObject stage = new JsonObject();
        stage.addProperty("type", "OBJECT");

        JsonObject stageProperties = new JsonObject();
        stageProperties.add("stageName", stringSchema(false));
        stageProperties.add("date", stringSchema(true));
        stageProperties.add("description", stringSchema(true));
        stage.add("properties", stageProperties);

        JsonArray stageRequired = new JsonArray();
        stageRequired.add("stageName");
        stage.add("required", stageRequired);

        JsonObject root = new JsonObject();
        root.addProperty("type", "OBJECT");

        JsonObject properties = new JsonObject();
        properties.add("hackathonName", stringSchema(true));

        JsonObject stagesProperty = new JsonObject();
        stagesProperty.addProperty("type", "ARRAY");
        stagesProperty.add("items", stage);
        properties.add("stages", stagesProperty);
        root.add("properties", properties);

        JsonArray required = new JsonArray();
        required.add("stages");
        root.add("required", required);
        return root;
    }

    private static JsonObject stringSchema(boolean nullable) {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "STRING");
        if (nullable) {
            schema.addProperty("nullable", true);
        }
        return schema;
    }

    /**
     * Pulls the model's text out of a Gemini {@code generateContent} response.
     *
     * <p>Handles a blocked prompt and the case where every candidate was filtered
     * for safety, so those report a clear message instead of looking malformed.
     */
    private static String readContent(String body) {
        JsonObject root;
        try {
            root = JsonParser.parseString(body).getAsJsonObject();
        } catch (RuntimeException e) {
            throw new AiScheduleException(AiScheduleException.Code.AI_MALFORMED_RESPONSE,
                    "The AI service returned an unreadable response. Please try again.", e);
        }
        if (root.has("error") && root.get("error").isJsonObject()) {
            System.err.println("AI schedule extraction received an error payload ("
                    + providerErrorType(body) + ").");
            throw new AiScheduleException(AiScheduleException.Code.AI_UNAVAILABLE,
                    "The AI service reported an error. Please try again.");
        }

        if (root.has("promptFeedback")) {
            JsonElement feedback = root.get("promptFeedback");
            if (feedback.isJsonObject()) {
                // Gemini's BlockReason values are SAFETY, BLOCKLIST, PROHIBITED_CONTENT,
                // SPII, IMAGE_SAFETY, OTHER and MALFORMED_FUNCTION_CALL. Any of them
                // means there is no candidate content to read.
                String blockReason = stringOrNull(feedback.getAsJsonObject(), "blockReason");
                if (blockReason != null) {
                    throw new AiScheduleException(AiScheduleException.Code.AI_UNAVAILABLE,
                            "The AI service declined to process that website content. Try a different page.");
                }
            }
        }

        JsonElement candidates = root.get("candidates");
        if (candidates == null || !candidates.isJsonArray() || candidates.getAsJsonArray().isEmpty()) {
            throw new AiScheduleException(AiScheduleException.Code.AI_MALFORMED_RESPONSE,
                    "The AI service returned an unexpected response shape. Please try again.");
        }

        StringBuilder text = new StringBuilder();
        boolean sawCandidate = false;
        for (JsonElement element : candidates.getAsJsonArray()) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject candidate = element.getAsJsonObject();
            String finishReason = stringOrNull(candidate, "finishReason");
            if ("SAFETY".equals(finishReason) || "RECITATION".equals(finishReason)
                    || "PROHIBITED_CONTENT".equals(finishReason)) {
                throw new AiScheduleException(AiScheduleException.Code.AI_UNAVAILABLE,
                        "The AI service could not return a schedule for that website content.");
            }
            JsonElement content = candidate.get("content");
            if (content == null || !content.isJsonObject()) {
                continue;
            }
            sawCandidate = true;
            JsonElement parts = content.getAsJsonObject().get("parts");
            if (parts == null || !parts.isJsonArray()) {
                continue;
            }
            for (JsonElement part : parts.getAsJsonArray()) {
                String chunk = asText(part.isJsonObject() ? part.getAsJsonObject().get("text") : null);
                if (chunk != null) {
                    text.append(chunk);
                }
            }
        }

        if (!sawCandidate) {
            throw new AiScheduleException(AiScheduleException.Code.AI_MALFORMED_RESPONSE,
                    "The AI service returned an unexpected response shape. Please try again.");
        }
        return text.toString().trim();
    }

    // ── Response parsing ──

    /**
     * Turns the model's JSON text into an {@link ExtractedSchedule}. Every field
     * is optional and loosely matched, because a model response must never crash
     * the feature; anything unusable is reported through notes instead.
     */
    ExtractedSchedule parseResponse(String rawJson) {
        String cleaned = stripCodeFence(rawJson);
        JsonElement root;
        try {
            root = JsonParser.parseString(cleaned);
        } catch (RuntimeException e) {
            throw new AiScheduleException(AiScheduleException.Code.AI_MALFORMED_RESPONSE,
                    "The AI response was not valid JSON. Please try again.", e);
        }

        JsonObject rootObject = null;
        JsonArray stageArray = null;

        if (root.isJsonArray()) {
            stageArray = root.getAsJsonArray();
        } else if (root.isJsonObject()) {
            rootObject = root.getAsJsonObject();
            for (String key : new String[]{"stages", "schedule", "events", "milestones", "deadlines", "importantDates"}) {
                JsonElement candidate = rootObject.get(key);
                if (candidate != null && candidate.isJsonArray()) {
                    stageArray = candidate.getAsJsonArray();
                    break;
                }
            }
            if (stageArray == null) {
                throw new AiScheduleException(AiScheduleException.Code.AI_MALFORMED_RESPONSE,
                        "The AI response did not contain a list of stages. Please try again.");
            }
        } else {
            throw new AiScheduleException(AiScheduleException.Code.AI_MALFORMED_RESPONSE,
                    "The AI response was not usable. Please try again.");
        }

        ExtractedSchedule schedule = new ExtractedSchedule();
        if (rootObject != null) {
            schedule.setHackathonName(asText(rootObject.get("hackathonName")));
        }

        int order = 0;
        for (JsonElement element : stageArray) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject item = element.getAsJsonObject();
            String reportedName = asText(pick(item, "stageName", "name", "stage", "title", "milestone"));
            String rawDate = asText(pick(item, "date", "deadline", "dateText", "dueDate", "endDate", "when"));
            String description = asText(pick(item, "description", "details", "note", "notes", "remarks"));
            if (reportedName == null || reportedName.isBlank()) {
                continue;
            }
            if (description != null && description.length() > 240) {
                description = description.substring(0, 240);
            }

            ExtractedStage stage = new ExtractedStage();
            stage.setReportedName(reportedName.trim());
            stage.setDescription(description);
            stage.setRawDate(rawDate);
            stage.setDate(AiDateParser.parse(rawDate));
            stage.setOrder(++order);

            String canonical = StageCatalog.match(reportedName, description);
            stage.setCanonicalName(canonical);
            stage.setRecognized(canonical != null);

            if (stage.getDate() == null) {
                stage.setSelected(false);
                stage.setSkipReason(rawDate == null || rawDate.isBlank()
                        ? "No date was stated on the website"
                        : "Date \"" + rawDate.trim() + "\" could not be read reliably");
            } else if (canonical == null) {
                stage.setSelected(false);
                stage.setSkipReason("Not one of the existing HackTrack stages");
            }
            schedule.getStages().add(stage);
        }

        if (schedule.getStages().isEmpty()) {
            throw new AiScheduleException(AiScheduleException.Code.NO_SCHEDULE_FOUND,
                    "No hackathon stages or deadlines were found on that website. You can still add stages manually.");
        }
        return schedule;
    }

    private static JsonElement pick(JsonObject object, String... keys) {
        for (String key : keys) {
            JsonElement element = object.get(key);
            if (element != null && !element.isJsonNull()) {
                return element;
            }
        }
        return null;
    }

    static String asText(JsonElement element) {
        if (element == null || element.isJsonNull()) {
            return null;
        }
        if (element.isJsonPrimitive()) {
            if (element.getAsJsonPrimitive().isBoolean()) {
                return null;
            }
            return element.getAsString();
        }
        return null;
    }

    private static String stripCodeFence(String raw) {
        String value = raw.trim();
        if (!value.startsWith("```")) {
            return value;
        }
        int firstNewline = value.indexOf('\n');
        int lastFence = value.lastIndexOf("```");
        if (firstNewline < 0 || lastFence <= firstNewline) {
            return value;
        }
        return value.substring(firstNewline + 1, lastFence).trim();
    }

    private static String trimTrailingSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    /**
     * Extracts only the provider's machine-readable error classification.
     *
     * <p>Provider error bodies routinely echo back parts of the API key, so the raw
     * text is never logged or returned to the browser. Only the short {@code type} and
     * {@code code} fields are kept, which is enough to diagnose a failure.
     */
    private static String providerErrorType(String body) {
        if (body == null || body.isBlank()) {
            return "no-error-detail";
        }
        try {
            JsonElement root = JsonParser.parseString(body);
            if (root.isJsonObject() && root.getAsJsonObject().has("error")
                    && root.getAsJsonObject().get("error").isJsonObject()) {
                JsonObject error = root.getAsJsonObject().getAsJsonObject("error");
                String type = stringOrNull(error, "type");
                String code = stringOrNull(error, "code");
                if (type != null || code != null) {
                    return (type == null ? "unknown" : type) + (code == null ? "" : "/" + code);
                }
            }
        } catch (RuntimeException ignored) {
            // Never echo an unparsable provider body.
        }
        return "no-error-detail";
    }

    private static String stringOrNull(JsonObject object, String field) {
        if (!object.has(field)) {
            return null;
        }
        JsonElement value = object.get(field);
        if (!value.isJsonPrimitive()) {
            return null;
        }
        String text = value.getAsString();
        if (text == null || text.isBlank()) {
            return null;
        }
        return text.length() > 60 ? text.substring(0, 60) : text;
    }
}
