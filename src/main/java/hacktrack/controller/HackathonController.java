package hacktrack.controller;

import hacktrack.dao.HackathonDAO;
import hacktrack.dao.HistoryDAO;
import hacktrack.dao.StageDAO;
import hacktrack.dao.EmailSettingsDAO;
import hacktrack.logic.ProgressionEngine;
import hacktrack.model.Hackathon;
import hacktrack.model.ParticipationHistory;
import hacktrack.model.Stage;
import hacktrack.status.StageStatus;
import hacktrack.service.GmailService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.ModelAndView;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
@CrossOrigin(origins = "*")
public class HackathonController {

    // ── Hackathon endpoints ──

    @GetMapping("/hackathons")
    public ResponseEntity<List<Map<String, Object>>> getAllHackathons(HttpServletRequest request) {
        Integer userId = AuthController.currentUserId(request);
        if (userId == null) return unauthorized();

        List<Hackathon> hackathons = HackathonDAO.getAllForOwner(userId);
        for (Hackathon h : hackathons) {
            List<Stage> stages = StageDAO.getByHackathonId(h.getId());
            h.setStageCount(stages.size());
            h.setStatusString(ProgressionEngine.calculateOverallStatus(h.getId()));
        }

        // Convert to list of maps for JSON serialization
        java.util.List<Map<String, Object>> result = new java.util.ArrayList<>();
        for (Hackathon h : hackathons) {
            Map<String, Object> map = new HashMap<>();
            map.put("id", h.getId());
            map.put("name", h.getName());
            map.put("websiteUrl", h.getWebsiteUrl());
            map.put("starred", h.isStarred());
            map.put("createdAt", h.getCreatedAt() != null ? h.getCreatedAt().toString() : null);
            map.put("stageCount", h.getStageCount());
            map.put("statusString", h.getStatusString());
            result.add(map);
        }
        return ResponseEntity.ok(result);
    }

    @GetMapping("/hackathons/{id}")
    public ResponseEntity<Map<String, Object>> getHackathon(@PathVariable int id, HttpServletRequest request) {
        Integer userId = AuthController.currentUserId(request);
        if (userId == null) return unauthorized();

        Hackathon h = HackathonDAO.getById(id);
        if (h == null) return ResponseEntity.notFound().build();
        if (h.getOwnerId() != userId) return forbidden();

        List<Stage> stages = StageDAO.getByHackathonId(h.getId());
        h.setStageCount(stages.size());
        h.setStatusString(ProgressionEngine.calculateOverallStatus(h.getId()));

        Map<String, Object> map = new HashMap<>();
        map.put("id", h.getId());
        map.put("name", h.getName());
        map.put("websiteUrl", h.getWebsiteUrl());
        map.put("starred", h.isStarred());
        map.put("createdAt", h.getCreatedAt() != null ? h.getCreatedAt().toString() : null);
        map.put("stageCount", h.getStageCount());
        map.put("statusString", h.getStatusString());
        return ResponseEntity.ok(map);
    }

    @PostMapping("/hackathons")
    public ResponseEntity<Map<String, Object>> createHackathon(@RequestBody Map<String, Object> body,
                                                               HttpServletRequest request) {
        Integer userId = AuthController.currentUserId(request);
        if (userId == null) return unauthorized();

        String name = (String) body.get("name");
        String url = (String) body.getOrDefault("websiteUrl", "");

        if (name == null || name.isBlank()) {
            return ResponseEntity.badRequest().build();
        }

        Hackathon h = new Hackathon(0, name.trim(), url != null ? url.trim() : "", false);
        h.setOwnerId(userId);
        int newId = HackathonDAO.insert(h);
        if (newId <= 0) return ResponseEntity.internalServerError().build();

        h.setId(newId);
        h.setStageCount(0);
        h.setStatusString("NO_STAGES");

        Map<String, Object> map = new HashMap<>();
        map.put("id", h.getId());
        map.put("name", h.getName());
        map.put("websiteUrl", h.getWebsiteUrl());
        map.put("starred", h.isStarred());
        map.put("createdAt", h.getCreatedAt().toString());
        map.put("stageCount", 0);
        map.put("statusString", "NO_STAGES");
        return ResponseEntity.ok(map);
    }

    @PutMapping("/hackathons/{id}")
    public ResponseEntity<Map<String, Object>> updateHackathon(@PathVariable int id, @RequestBody Map<String, Object> body,
                                                               HttpServletRequest request) {
        Integer userId = AuthController.currentUserId(request);
        if (userId == null) return unauthorized();

        Hackathon h = HackathonDAO.getById(id);
        if (h == null) return ResponseEntity.notFound().build();
        if (h.getOwnerId() != userId) return forbidden();

        h.setName((String) body.getOrDefault("name", h.getName()));
        h.setWebsiteUrl((String) body.getOrDefault("websiteUrl", h.getWebsiteUrl()));
        if (body.containsKey("starred")) {
            h.setStarred(Boolean.TRUE.equals(body.get("starred")));
        }

        HackathonDAO.update(h);

        Map<String, Object> map = new HashMap<>();
        map.put("id", h.getId());
        map.put("name", h.getName());
        map.put("websiteUrl", h.getWebsiteUrl());
        map.put("starred", h.isStarred());
        return ResponseEntity.ok(map);
    }

    @DeleteMapping("/hackathons/{id}")
    public ResponseEntity<Void> deleteHackathon(@PathVariable int id, HttpServletRequest request) {
        Integer userId = AuthController.currentUserId(request);
        if (userId == null) return ResponseEntity.status(401).build();

        Hackathon h = HackathonDAO.getById(id);
        if (h == null) return ResponseEntity.notFound().build();
        if (h.getOwnerId() != userId) return ResponseEntity.status(403).build();

        boolean deleted = HackathonDAO.delete(id);
        return deleted ? ResponseEntity.ok().build() : ResponseEntity.notFound().build();
    }

    @PutMapping("/hackathons/{id}/toggle-star")
    public ResponseEntity<Map<String, Object>> toggleStar(@PathVariable int id, HttpServletRequest request) {
        Integer userId = AuthController.currentUserId(request);
        if (userId == null) return unauthorized();

        Hackathon h = HackathonDAO.getById(id);
        if (h == null) return ResponseEntity.notFound().build();
        if (h.getOwnerId() != userId) return forbidden();

        boolean toggled = HackathonDAO.toggleStar(id);
        if (!toggled) return ResponseEntity.notFound().build();

        Map<String, Object> map = new HashMap<>();
        map.put("id", h.getId());
        map.put("starred", h.isStarred());
        return ResponseEntity.ok(map);
    }

    // ── Stage endpoints ──

    @GetMapping("/hackathons/{hackathonId}/stages")
    public ResponseEntity<List<Map<String, Object>>> getStages(@PathVariable int hackathonId, HttpServletRequest request) {
        Integer userId = AuthController.currentUserId(request);
        if (userId == null) return unauthorized();
        if (!HackathonDAO.isOwnedBy(hackathonId, userId)) return ownershipFailure(hackathonId);

        List<Stage> stages = StageDAO.getByHackathonId(hackathonId);
        java.util.List<Map<String, Object>> result = new java.util.ArrayList<>();
        for (Stage s : stages) {
            Map<String, Object> map = stageToMap(s);
            result.add(map);
        }
        return ResponseEntity.ok(result);
    }

    @PostMapping("/hackathons/{hackathonId}/stages")
    public ResponseEntity<Map<String, Object>> createStage(@PathVariable int hackathonId, @RequestBody Map<String, Object> body,
                                                           HttpServletRequest request) {
        Integer userId = AuthController.currentUserId(request);
        if (userId == null) return unauthorized();

        Hackathon h = HackathonDAO.getById(hackathonId);
        if (h == null) return ResponseEntity.notFound().build();
        if (h.getOwnerId() != userId) return forbidden();

        String name = (String) body.get("name");
        String deadline = (String) body.get("deadline");
        if (name == null || deadline == null) return ResponseEntity.badRequest().build();

        // Auto-assign order
        List<Stage> existing = StageDAO.getByHackathonId(hackathonId);
        int nextOrder = 1;
        if (!existing.isEmpty()) {
            nextOrder = existing.get(existing.size() - 1).getStageOrder() + 1;
        }

        Stage stage = new Stage(0, hackathonId, nextOrder, name.trim(),
                java.time.LocalDate.parse(deadline), StageStatus.UPCOMING, 3);
        StageDAO.insert(stage);

        Map<String, Object> map = stageToMap(stage);
        return ResponseEntity.ok(map);
    }

    @PutMapping("/stages/{id}")
    public ResponseEntity<Map<String, Object>> updateStage(@PathVariable int id, @RequestBody Map<String, Object> body,
                                                           HttpServletRequest request) {
        Integer userId = AuthController.currentUserId(request);
        if (userId == null) return unauthorized();

        Stage stage = StageDAO.getById(id);
        if (stage == null) return ResponseEntity.notFound().build();
        if (!HackathonDAO.isOwnedBy(stage.getHackathonId(), userId)) return forbidden();

        if (body.containsKey("name")) {
            stage.setName((String) body.get("name"));
        }
        if (body.containsKey("deadline")) {
            stage.setDeadline(java.time.LocalDate.parse((String) body.get("deadline")));
        }

        StageStatus oldStatus = stage.getStatus();

        if (body.containsKey("status")) {
            String statusStr = (String) body.get("status");
            StageStatus newStatus = StageStatus.valueOf(statusStr);
            stage.setStatus(newStatus);

            // Trigger progression engine logic
            if (newStatus != oldStatus) {
                switch (newStatus) {
                    case ELIMINATED:
                        ProgressionEngine.handleElimination(stage.getHackathonId(), stage.getStageOrder());
                        break;
                    case QUALIFIED:
                        ProgressionEngine.handleQualification(stage.getHackathonId(), stage.getStageOrder());
                        break;
                    case SUBMITTED:
                        ProgressionEngine.handleSubmitted(stage.getHackathonId(), stage.getStageOrder());
                        break;
                    default:
                        break;
                }
            }
        }

        if (body.containsKey("reminderOffsetDays")) {
            stage.setReminderOffsetDays((Integer) body.get("reminderOffsetDays"));
        }

        StageDAO.update(stage);

        // Auto-record participation history when overall status is terminal
        String overallStatus = ProgressionEngine.calculateOverallStatus(stage.getHackathonId());
        if ("COMPLETED".equals(overallStatus) || "ELIMINATED".equals(overallStatus)) {
            if (!HistoryDAO.hasHistoryForHackathon(stage.getHackathonId())) {
                Hackathon hackathon = HackathonDAO.getById(stage.getHackathonId());
                String name = hackathon != null ? hackathon.getName() : "Hackathon";
                String outcome = "COMPLETED".equals(overallStatus)
                        ? "Completed — " + name
                        : "Eliminated — " + name;
                HistoryDAO.insert(stage.getHackathonId(), outcome);
            }
        }

        // Re-read stage after progression engine may have modified related stages
        stage = StageDAO.getById(id);
        Map<String, Object> map = stageToMap(stage);
        return ResponseEntity.ok(map);
    }

    @DeleteMapping("/stages/{id}")
    public ResponseEntity<Void> deleteStage(@PathVariable int id, HttpServletRequest request) {
        Integer userId = AuthController.currentUserId(request);
        if (userId == null) return ResponseEntity.status(401).build();

        Stage stage = StageDAO.getById(id);
        if (stage == null) return ResponseEntity.notFound().build();
        if (!HackathonDAO.isOwnedBy(stage.getHackathonId(), userId)) return ResponseEntity.status(403).build();

        boolean deleted = StageDAO.delete(id);
        return deleted ? ResponseEntity.ok().build() : ResponseEntity.notFound().build();
    }

    // ── Next relevant stage ──

    @GetMapping("/hackathons/{hackathonId}/next-stage")
    public ResponseEntity<Map<String, Object>> getNextStage(@PathVariable int hackathonId, HttpServletRequest request) {
        Integer userId = AuthController.currentUserId(request);
        if (userId == null) return unauthorized();
        if (!HackathonDAO.isOwnedBy(hackathonId, userId)) return ownershipFailure(hackathonId);

        Stage next = ProgressionEngine.getNextRelevantStage(hackathonId);
        if (next == null) return ResponseEntity.ok(null);
        return ResponseEntity.ok(stageToMap(next));
    }

    // ── Overall status ──

    @GetMapping("/hackathons/{hackathonId}/overall-status")
    public ResponseEntity<Map<String, String>> getOverallStatus(@PathVariable int hackathonId, HttpServletRequest request) {
        Integer userId = AuthController.currentUserId(request);
        if (userId == null) return ResponseEntity.status(401).body(Map.of(
                "error", "Authentication required.", "code", "UNAUTHENTICATED"));
        if (!HackathonDAO.isOwnedBy(hackathonId, userId)) {
            return ResponseEntity.status(403).body(Map.of(
                    "error", "You do not have access to this hackathon.", "code", "FORBIDDEN"));
        }

        String status = ProgressionEngine.calculateOverallStatus(hackathonId);
        Map<String, String> map = new HashMap<>();
        map.put("status", status);
        return ResponseEntity.ok(map);
    }

    // ── History endpoints ──

    @GetMapping("/history")
    public ResponseEntity<List<Map<String, Object>>> getHistory(HttpServletRequest request) {
        Integer userId = AuthController.currentUserId(request);
        if (userId == null) return unauthorized();

        List<ParticipationHistory> history = HistoryDAO.getAllForOwner(userId);
        java.util.List<Map<String, Object>> result = new java.util.ArrayList<>();
        for (ParticipationHistory ph : history) {
            Map<String, Object> map = new HashMap<>();
            map.put("id", ph.getId());
            map.put("hackathonId", ph.getHackathonId());
            map.put("hackathonName", ph.getHackathonName());
            map.put("finalOutcome", ph.getFinalOutcome());
            map.put("completedAt", ph.getCompletedAt() != null ? ph.getCompletedAt().toString() : null);
            result.add(map);
        }
        return ResponseEntity.ok(result);
    }

    @PostMapping("/history")
    public ResponseEntity<Map<String, Object>> addHistory(@RequestBody Map<String, Object> body,
                                                          HttpServletRequest request) {
        Integer userId = AuthController.currentUserId(request);
        if (userId == null) return unauthorized();

        Integer hackathonId = (Integer) body.get("hackathonId");
        String outcome = (String) body.get("finalOutcome");
        if (hackathonId == null || outcome == null) return ResponseEntity.badRequest().build();
        if (!HackathonDAO.isOwnedBy(hackathonId, userId)) return forbidden();

        int id = HistoryDAO.insert(hackathonId, outcome);
        if (id <= 0) return ResponseEntity.internalServerError().build();

        Map<String, Object> map = new HashMap<>();
        map.put("id", id);
        map.put("hackathonId", hackathonId);
        map.put("finalOutcome", outcome);
        return ResponseEntity.ok(map);
    }

    @DeleteMapping("/history/{id}")
    public ResponseEntity<Void> deleteHistory(@PathVariable int id, HttpServletRequest request) {
        Integer userId = AuthController.currentUserId(request);
        if (userId == null) return ResponseEntity.status(401).build();

        Integer hackathonId = HistoryDAO.getHackathonId(id);
        if (hackathonId == null) return ResponseEntity.notFound().build();
        if (!HackathonDAO.isOwnedBy(hackathonId, userId)) return ResponseEntity.status(403).build();

        boolean deleted = HistoryDAO.delete(id);
        return deleted ? ResponseEntity.ok().build() : ResponseEntity.notFound().build();
    }

    // ── Gmail endpoints ──

    @GetMapping("/gmail/status")
    public ResponseEntity<Map<String, Object>> getGmailStatus(HttpServletRequest request) {
        Integer userId = AuthController.currentUserId(request);
        if (userId == null) return unauthorized();

        Map<String, Object> map = new HashMap<>();
        map.put("connected", GmailService.isConnected());
        map.put("email", GmailService.getUserEmail());
        map.put("recipientEmail", EmailSettingsDAO.getRecipientEmail(userId));
        map.put("lastReminder", GmailService.getLastReminderInfo());
        return ResponseEntity.ok(map);
    }

    @GetMapping("/gmail/connect")
    public ModelAndView connectGmail() {
        String authUrl = GmailService.getAuthorizationUrl();
        if (authUrl == null) {
            ModelAndView errorView = new ModelAndView("redirect:/?gmail=error");
            return errorView;
        }
        return new ModelAndView("redirect:" + authUrl);
    }

    @GetMapping("/gmail/callback")
    public ModelAndView gmailCallback(@RequestParam("code") String code,
                                       @RequestParam(value = "error", required = false) String error) {
        if (error != null) {
            return new ModelAndView("redirect:/?gmail=denied");
        }
        boolean success = GmailService.handleCallback(code);
        return new ModelAndView("redirect:/?gmail=" + (success ? "connected" : "error"));
    }

    @PostMapping("/gmail/disconnect")
    public ResponseEntity<Map<String, Object>> disconnectGmail() {
        boolean success = GmailService.disconnect();
        Map<String, Object> map = new HashMap<>();
        map.put("success", success);
        return ResponseEntity.ok(map);
    }

    @PostMapping("/gmail/recipient")
    public ResponseEntity<Map<String, Object>> setRecipientEmail(@RequestBody Map<String, String> body,
                                                                 HttpServletRequest request) {
        Integer userId = AuthController.currentUserId(request);
        if (userId == null) return unauthorized();

        String email = body.get("email");
        if (email == null || email.isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        boolean saved = EmailSettingsDAO.setRecipientEmail(userId, email.trim());
        Map<String, Object> map = new HashMap<>();
        map.put("success", saved);
        map.put("recipientEmail", email.trim());
        return ResponseEntity.ok(map);
    }

    @PostMapping("/gmail/test")
    public ResponseEntity<Map<String, Object>> sendTestEmail(HttpServletRequest request) {
        Integer userId = AuthController.currentUserId(request);
        if (userId == null) return unauthorized();

        Map<String, Object> map = new HashMap<>();
        String recipient = EmailSettingsDAO.getRecipientEmail(userId);
        if (!GmailService.isConnected()) {
            map.put("success", false);
            map.put("message", "Gmail is not connected. Click 'Connect Gmail' first.");
            return ResponseEntity.ok(map);
        }
        if (GmailService.getUserEmail() == null) {
            map.put("success", false);
            map.put("message", "Gmail connected but could not determine sender email. "
                    + "Disconnect and reconnect to re-authorize with the correct scopes.");
            return ResponseEntity.ok(map);
        }
        if (recipient == null || recipient.isBlank()) {
            map.put("success", false);
            map.put("message", "No recipient email configured. Enter an email address above and click Save.");
            return ResponseEntity.ok(map);
        }
        boolean sent = GmailService.sendReminderEmail(recipient,
                "Test Hackathon", "", "Test Stage",
                java.time.LocalDate.now().plusDays(3), 3);
        if (sent) {
            map.put("success", true);
            map.put("message", "Test email sent successfully to " + recipient);
        } else {
            String lastInfo = GmailService.getLastReminderInfo();
            String errorDetail = null;
            if (lastInfo != null && lastInfo.startsWith("error:")) {
                errorDetail = lastInfo.substring("error:".length());
            }
            if (errorDetail != null) {
                map.put("success", false);
                map.put("message", "Gmail API error: " + errorDetail);
            } else {
                map.put("success", false);
                map.put("message", "Gmail API rejected the request. Check that the Gmail API is enabled "
                        + "in Google Cloud Console and your app is in Testing mode with this email added as a test user.");
            }
        }
        return ResponseEntity.ok(map);
    }

    // ── Helpers ──

    @SuppressWarnings("unchecked")
    private static <T> ResponseEntity<T> unauthorized() {
        return (ResponseEntity<T>) ResponseEntity.status(401).body(Map.of(
                "error", "Authentication required.", "code", "UNAUTHENTICATED"));
    }

    @SuppressWarnings("unchecked")
    private static <T> ResponseEntity<T> forbidden() {
        return (ResponseEntity<T>) ResponseEntity.status(403).body(Map.of(
                "error", "You do not have access to this resource.", "code", "FORBIDDEN"));
    }

    /** 404 when the hackathon does not exist, 403 when it belongs to someone else. */
    @SuppressWarnings("unchecked")
    private static <T> ResponseEntity<T> ownershipFailure(int hackathonId) {
        if (HackathonDAO.getById(hackathonId) == null) {
            return (ResponseEntity<T>) ResponseEntity.notFound().build();
        }
        return forbidden();
    }

    private Map<String, Object> stageToMap(Stage s) {
        Map<String, Object> map = new HashMap<>();
        map.put("id", s.getId());
        map.put("hackathonId", s.getHackathonId());
        map.put("stageOrder", s.getStageOrder());
        map.put("name", s.getName());
        map.put("deadline", s.getDeadline() != null ? s.getDeadline().toString() : null);
        map.put("reminderOffsetDays", s.getReminderOffsetDays());

        StageStatus realStatus = s.getStatus() != null ? s.getStatus() : StageStatus.UPCOMING;
        String realStatusName = realStatus.name();
        String realStatusDisplay = realStatus.toString();

        String displayStatusName = realStatusName;
        String displayStatusDisplay = realStatusDisplay;
        if (realStatus == StageStatus.UPCOMING
                && s.getDeadline() != null
                && s.getDeadline().isBefore(LocalDate.now())) {
            displayStatusName = "OVERDUE";
            displayStatusDisplay = "Overdue";
        }

        map.put("status", displayStatusDisplay);
        map.put("statusEnum", displayStatusName);
        map.put("dbStatus", realStatusName);
        return map;
    }
}
