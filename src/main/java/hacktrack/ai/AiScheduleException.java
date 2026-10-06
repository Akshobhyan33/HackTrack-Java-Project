package hacktrack.ai;

/**
 * Failure of the AI schedule extraction pipeline.
 *
 * Every failure carries a short machine-readable code and a message that is safe
 * to show in the frontend. No API key, authorization header, raw provider
 * response or other sensitive backend detail is ever placed in the message.
 */
public class AiScheduleException extends RuntimeException {

    public enum Code {
        /** GEMINI_API_KEY environment variable is not set. */
        AI_KEY_MISSING,
        /** The supplied API key was rejected by the AI service. */
        AI_KEY_INVALID,
        /** The AI service did not answer within the configured timeout. */
        AI_TIMEOUT,
        /** The AI service is rate limiting requests. */
        AI_RATE_LIMIT,
        /** The AI service returned an error or is unavailable. */
        AI_UNAVAILABLE,
        /** The AI service answered with something that is not usable JSON. */
        AI_MALFORMED_RESPONSE,
        /** The supplied URL is not a usable http/https web address. */
        INVALID_URL,
        /** The website could not be reached or returned an error. */
        WEBSITE_UNAVAILABLE,
        /** The website was reachable but contained no identifiable schedule data. */
        NO_SCHEDULE_FOUND
    }

    private final Code code;

    public AiScheduleException(Code code, String userMessage) {
        super(userMessage);
        this.code = code;
    }

    public AiScheduleException(Code code, String userMessage, Throwable cause) {
        super(userMessage, cause);
        this.code = code;
    }

    public Code getCode() {
        return code;
    }

    /** HTTP status the controller should answer with for this failure. */
    public int getHttpStatus() {
        return switch (code) {
            case AI_KEY_MISSING, AI_KEY_INVALID, INVALID_URL -> 400;
            case AI_RATE_LIMIT -> 429;
            case AI_TIMEOUT -> 504;
            case AI_UNAVAILABLE, AI_MALFORMED_RESPONSE, WEBSITE_UNAVAILABLE, NO_SCHEDULE_FOUND -> 502;
        };
    }
}
