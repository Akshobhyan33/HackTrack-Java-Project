package hacktrack.controller;

import hacktrack.ai.AiScheduleException;
import hacktrack.ai.AiScheduleService;
import hacktrack.model.Hackathon;
import hacktrack.dao.HackathonDAO;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * REST endpoints for AI-powered hackathon schedule extraction.
 *
 * Two steps on purpose: extraction only returns a preview, and nothing reaches
 * the database until the user applies the reviewed schedule. The controller
 * stays thin and delegates to {@link AiScheduleService}, which in turn uses the
 * existing stage DAO.
 */
@RestController
@RequestMapping("/api")
@CrossOrigin(origins = "*")
public class AiScheduleController {

    private final AiScheduleService aiScheduleService;

    public AiScheduleController(AiScheduleService aiScheduleService) {
        this.aiScheduleService = aiScheduleService;
    }

    /**
     * Reports whether AI extraction is available. Never exposes the API key.
     */
    @GetMapping("/ai/status")
    public ResponseEntity<Map<String, Object>> getStatus() {
        Map<String, Object> map = new HashMap<>();
        map.put("configured", aiScheduleService.isConfigured());
        map.put("provider", "Google Gemini");
        map.put("requiredEnvironmentVariable", "GEMINI_API_KEY");
        if (!aiScheduleService.isConfigured()) {
            map.put("message", "AI extraction is not configured. Set the GEMINI_API_KEY environment "
                    + "variable and restart HackTrack.");
        }
        return ResponseEntity.ok(map);
    }

    /**
     * Analyses the hackathon's official website and returns a preview of the
     * stages and dates that were found. Does not modify the database.
     */
    @PostMapping("/hackathons/{id}/ai-schedule")
    public ResponseEntity<Object> analyze(@PathVariable int id, @RequestBody(required = false) Map<String, Object> body,
                                          HttpServletRequest request) {
        Map<String, Object> input = body != null ? body : new HashMap<>();
        try {
            ResponseEntity<Object> denied = requireOwnership(id, request);
            if (denied != null) return denied;
            Hackathon hackathon = HackathonDAO.getById(id);
            if (hackathon == null) {
                return ResponseEntity.status(404).body(errorBody("HACKATHON_NOT_FOUND", "Hackathon not found."));
            }
            return ResponseEntity.ok(aiScheduleService.preview(
                    id,
                    asString(input.get("name")),
                    asString(input.get("url"))));
        } catch (AiScheduleException e) {
            return ResponseEntity.status(e.getHttpStatus()).body(errorBody(e.getCode().name(), e.getMessage()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(errorBody("INVALID_REQUEST", e.getMessage()));
        } catch (RuntimeException e) {
            System.err.println("Unexpected AI schedule extraction failure: " + e.getClass().getSimpleName());
            return ResponseEntity.status(500).body(errorBody("UNEXPECTED_ERROR",
                    "Something went wrong while analysing that website. Please try again."));
        }
    }

    /**
     * Saves the stages the user selected from the preview into the existing
     * HackTrack stages, from where the existing stage logic and Gmail reminder
     * system pick them up.
     */
    @PostMapping("/hackathons/{id}/ai-schedule/apply")
    public ResponseEntity<Object> apply(@PathVariable int id, @RequestBody(required = false) Map<String, Object> body,
                                        HttpServletRequest request) {
        Map<String, Object> input = body != null ? body : new HashMap<>();
        try {
            ResponseEntity<Object> denied = requireOwnership(id, request);
            if (denied != null) return denied;
            Hackathon hackathon = HackathonDAO.getById(id);
            if (hackathon == null) {
                return ResponseEntity.status(404).body(errorBody("HACKATHON_NOT_FOUND", "Hackathon not found."));
            }
            return ResponseEntity.ok(aiScheduleService.apply(
                    id,
                    asString(input.get("name")),
                    asString(input.get("url")),
                    parseSelections(input.get("stages")),
                    Boolean.TRUE.equals(input.get("overwriteExisting")),
                    Boolean.TRUE.equals(input.get("allowCustomStages"))));
        } catch (AiScheduleException e) {
            return ResponseEntity.status(e.getHttpStatus()).body(errorBody(e.getCode().name(), e.getMessage()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(errorBody("INVALID_REQUEST", e.getMessage()));
        } catch (RuntimeException e) {
            System.err.println("Unexpected AI schedule apply failure: " + e.getClass().getSimpleName());
            return ResponseEntity.status(500).body(errorBody("UNEXPECTED_ERROR",
                    "Something went wrong while saving the schedule. Nothing was changed."));
        }
    }

    // ── Helpers ──

    /**
     * Returns a 401/403/404 response when the caller may not work on this
     * hackathon, or null when the caller owns it.
     */
    private static ResponseEntity<Object> requireOwnership(int hackathonId, HttpServletRequest request) {
        Integer userId = AuthController.currentUserId(request);
        if (userId == null) {
            return ResponseEntity.status(401).body(errorBody("UNAUTHENTICATED", "Authentication required."));
        }
        Hackathon hackathon = HackathonDAO.getById(hackathonId);
        if (hackathon == null) {
            return ResponseEntity.status(404).body(errorBody("HACKATHON_NOT_FOUND", "Hackathon not found."));
        }
        if (hackathon.getOwnerId() != userId) {
            return ResponseEntity.status(403).body(errorBody("FORBIDDEN",
                    "You do not have access to this hackathon."));
        }
        return null;
    }

    private static List<AiScheduleService.StageSelection> parseSelections(Object raw) {
        List<AiScheduleService.StageSelection> selections = new ArrayList<>();
        if (!(raw instanceof List<?> list)) {
            return selections;
        }
        for (Object entry : list) {
            if (!(entry instanceof Map<?, ?> map)) {
                continue;
            }
            AiScheduleService.StageSelection selection = new AiScheduleService.StageSelection();
            selection.setStageName(asString(map.get("stageName")));
            selection.setDate(asString(map.get("date")));
            selection.setDescription(asString(map.get("description")));
            selections.add(selection);
        }
        return selections;
    }

    private static String asString(Object value) {
        return value instanceof String s ? s : null;
    }

    private static Map<String, Object> errorBody(String code, String message) {
        Map<String, Object> map = new HashMap<>();
        map.put("error", message != null ? message : "The request could not be completed.");
        map.put("code", code);
        return map;
    }
}
