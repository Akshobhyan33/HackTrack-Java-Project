package hacktrack.logic;

import hacktrack.dao.StageDAO;
import hacktrack.dao.HackathonDAO;
import hacktrack.dao.Database;
import hacktrack.dao.EmailSettingsDAO;
import hacktrack.status.StageStatus;
import hacktrack.model.Stage;
import hacktrack.model.Hackathon;
import hacktrack.service.GmailService;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Reminder Logic for HackTrack.
 * 
 * Reminder behavior:
 * - Default reminder offset: 3 days before deadline
 * - Remind only for UPCOMING stages within the reminder window
 * - Do NOT remind for ELIMINATED, NOT_APPLICABLE, or COMPLETED stages
 * - Check elimination blocking before firing a reminder
 * - Handle overdue stages separately
 * - Prevent duplicate reminders via reminder_history table
 * - Send email reminders via Gmail API when connected
 */
public class ReminderLogic {

    private static final int DEFAULT_REMINDER_OFFSET = 3;

    public static int checkAndFireReminders() {
        int remindersFired = 0;

        String recipientEmail = EmailSettingsDAO.getRecipientEmail();
        boolean gmailConnected = GmailService.isConnected();

        List<Hackathon> hackathons = HackathonDAO.getAll();

        for (Hackathon hackathon : hackathons) {
            List<Stage> stages = StageDAO.getByHackathonId(hackathon.getId());

            for (Stage stage : stages) {
                if (!stage.canReceiveReminders()) {
                    continue;
                }

                if (ProgressionEngine.areRemindersSuppressed(hackathon.getId(), stage.getStageOrder())) {
                    stage.setStatus(StageStatus.NOT_APPLICABLE);
                    StageDAO.update(stage);
                    continue;
                }

                LocalDate today = LocalDate.now();
                long daysUntilDeadline = java.time.temporal.ChronoUnit.DAYS.between(today, stage.getDeadline());
                boolean isOverdue = daysUntilDeadline < 0;

                if (isOverdue) {
                    if (!isDuplicateReminder(hackathon.getId(), stage.getId(), stage.getReminderOffsetDays())) {
                        fireReminder(hackathon, stage, -1, recipientEmail, gmailConnected);
                        remindersFired++;
                    }
                } else if (daysUntilDeadline <= stage.getReminderOffsetDays() && daysUntilDeadline >= 0) {
                    if (!isDuplicateReminder(hackathon.getId(), stage.getId(), stage.getReminderOffsetDays())) {
                        fireReminder(hackathon, stage, (int) daysUntilDeadline, recipientEmail, gmailConnected);
                        remindersFired++;
                    }
                }
            }
        }

        return remindersFired;
    }

    private static void fireReminder(Hackathon hackathon, Stage stage, int daysUntilDeadline,
            String recipientEmail, boolean gmailConnected) {
        System.out.println("REMINDER: Hackathon - " + hackathon.getName());
        System.out.println("  Stage: " + stage.getName());
        System.out.println("  Deadline: " + stage.getDeadline());
        System.out.println("  Days until deadline: " + daysUntilDeadline);
        System.out.println("  Status: " + stage.getStatus());

        if (gmailConnected && recipientEmail != null && !recipientEmail.isBlank()) {
            boolean sent = GmailService.sendReminderEmail(recipientEmail,
                    hackathon.getName(), hackathon.getWebsiteUrl(),
                    stage.getName(), stage.getDeadline(), daysUntilDeadline);
            if (sent) {
                storeReminderHistory(hackathon, stage, "gmail");
            } else {
                storeReminderHistory(hackathon, stage, "gmail-failed");
            }
        } else {
            storeReminderHistory(hackathon, stage, "in-app");
        }
    }

    private static void storeReminderHistory(Hackathon hackathon, Stage stage, String method) {
        String sql = "INSERT INTO reminder_history (hackathonId, stageId, offsetDays, sentAt, deliveryMethod, success) VALUES (?, ?, ?, ?, ?, ?)";
        try (java.sql.Connection conn = Database.getConnection();
             java.sql.PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setInt(1, hackathon.getId());
            pstmt.setInt(2, stage.getId());
            pstmt.setInt(3, stage.getReminderOffsetDays());
            pstmt.setString(4, LocalDateTime.now().toString());
            pstmt.setString(5, method);
            pstmt.setInt(6, 1);

            pstmt.executeUpdate();
        } catch (Exception e) {
            System.err.println("Error storing reminder history: " + e.getMessage());
        }
    }

    private static boolean isDuplicateReminder(int hackathonId, int stageId, int offsetDays) {
        String sql = "SELECT COUNT(*) FROM reminder_history WHERE hackathonId = ? AND stageId = ? AND offsetDays = ?";
        try (java.sql.Connection conn = Database.getConnection();
             java.sql.PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setInt(1, hackathonId);
            pstmt.setInt(2, stageId);
            pstmt.setInt(3, offsetDays);

            try (java.sql.ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt(1) > 0;
                }
            }
        } catch (Exception e) {
            System.err.println("Error checking duplicate reminder: " + e.getMessage());
        }
        return false;
    }

    public static int getDefaultReminderOffset() {
        return DEFAULT_REMINDER_OFFSET;
    }
}
