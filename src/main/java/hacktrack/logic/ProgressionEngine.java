package hacktrack.logic;

import hacktrack.dao.StageDAO;
import hacktrack.model.Hackathon;
import hacktrack.model.Stage;
import hacktrack.status.StageStatus;
import java.util.List;

/**
 * Progression Engine for HackTrack.
 * Handles stage status transitions and elimination-aware reminder gating.
 * 
 * Core rules:
 * - ELIMINATED: All subsequent stages in same hackathon auto-set to NOT_APPLICABLE
 *   and their reminders stop. This is the blocking mechanism.
 * - NOT_APPLICABLE (manual): Setting one stage to NOT_APPLICABLE does NOT
 *   automatically invalidate later stages. This is a deliberate distinction.
 * - QUALIFIED: The next stage in sequence becomes UPCOMING.
 * - SUBMITTED: Keeps the next stage as UPCOMING (user is still in progress).
 */
public class ProgressionEngine {

    /**
     * Handle when a stage status changes to ELIMINATED.
     * All subsequent stages in the same hackathon are auto-set to NOT_APPLICABLE.
     *
     * @param hackathonId the hackathon containing the stages
     * @param eliminatedStageOrder the stage order of the eliminated stage
     * @return true if the operation succeeded
     */
    public static boolean handleElimination(int hackathonId, int eliminatedStageOrder) {
        // Get all stages for this hackathon, ordered by stageOrder
        List<Stage> stages = StageDAO.getByHackathonId(hackathonId);

        boolean modified = false;
        for (Stage stage : stages) {
            // Only affect stages AFTER the eliminated one, and only if they aren't already N/A or ELIMINATED
            if (stage.getStageOrder() > eliminatedStageOrder) {
                if (stage.getStatus() != StageStatus.NOT_APPLICABLE && stage.getStatus() != StageStatus.ELIMINATED) {
                    stage.setStatus(StageStatus.NOT_APPLICABLE);
                    StageDAO.update(stage);
                    modified = true;
                }
            }
        }
        return modified;
    }

    /**
     * Handle when a stage status changes to QUALIFIED.
     * The next stage in sequence becomes UPCOMING (if it exists).
     *
     * @param hackathonId the hackathon containing the stages
     * @param qualifiedStageOrder the stage order of the qualified stage
     * @return true if a next stage was set to UPCOMING
     */
    public static boolean handleQualification(int hackathonId, int qualifiedStageOrder) {
        List<Stage> stages = StageDAO.getByHackathonId(hackathonId);

        // Find the next stage after the qualified one
        for (Stage stage : stages) {
            if (stage.getStageOrder() == qualifiedStageOrder + 1) {
                // Set this next stage to UPCOMING
                stage.setStatus(StageStatus.UPCOMING);
                return StageDAO.update(stage);
            }
        }
        // No next stage exists - hackathon is complete
        return false;
    }

    /**
     * Handle when a stage status changes to SUBMITTED.
     * The next stage remains/becomes UPCOMING (user is still in progress).
     * This method ensures the next stage is properly set.
     *
     * @param hackathonId the hackathon containing the stages
     * @param submittedStageOrder the stage order of the submitted stage
     * @return true if operation succeeded
     */
    public static boolean handleSubmitted(int hackathonId, int submittedStageOrder) {
        List<Stage> stages = StageDAO.getByHackathonId(hackathonId);

        // Find the next stage and set it to UPCOMING
        for (Stage stage : stages) {
            if (stage.getStageOrder() == submittedStageOrder + 1) {
                stage.setStatus(StageStatus.UPCOMING);
                return StageDAO.update(stage);
            }
        }
        return false;
    }

    /**
     * Manually set a specific stage to NOT_APPLICABLE.
     * This does NOT cascade to later stages - the user must explicitly set each one.
     *
     * @param hackathonId the hackathon containing the stages
     * @param stageOrder the stage order to set as NOT_APPLICABLE
     * @return true if the stage was found and updated
     */
    public static boolean setManualNotApplicable(int hackathonId, int stageOrder) {
        List<Stage> stages = StageDAO.getByHackathonId(hackathonId);
        for (Stage stage : stages) {
            if (stage.getStageOrder() == stageOrder) {
                stage.setStatus(StageStatus.NOT_APPLICABLE);
                return StageDAO.update(stage);
            }
        }
        return false;
    }

    /**
     * Determine the next relevant stage for reminder purposes.
     * Returns the first stage with status UPCOMING or SUBMITTED,
     * skipping any NOT_APPLICABLE or ELIMINATED stages.
     *
     * @param hackathonId the hackathon to check
     * @return the next relevant stage, or null if none found
     */
    public static Stage getNextRelevantStage(int hackathonId) {
        return StageDAO.getNextRelevantStage(hackathonId);
    }

    /**
     * Calculate the overall hackathon status based on all stage statuses.
     *
     * @param hackathonId the hackathon to evaluate
     * @return one of: "ACTIVE", "ELIMINATED", "COMPLETED", "NO_STAGES"
     */
    public static String calculateOverallStatus(int hackathonId) {
        List<Stage> stages = StageDAO.getByHackathonId(hackathonId);
        if (stages.isEmpty()) {
            return "NO_STAGES";
        }

        boolean hasUpcoming = false;
        boolean hasSubmitted = false;
        boolean hasEliminated = false;
        boolean allNOrComplete = true;

        for (Stage stage : stages) {
            switch (stage.getStatus()) {
                case UPCOMING:
                    hasUpcoming = true;
                    allNOrComplete = false;
                    break;
                case SUBMITTED:
                    hasSubmitted = true;
                    allNOrComplete = false;
                    break;
                case ELIMINATED:
                    hasEliminated = true;
                    allNOrComplete = false;
                    break;
                case NOT_APPLICABLE:
                    // N/A stages don't affect the overall status calculation
                    break;
                case COMPLETED:
                    // Completed stages are fine, but don't make it "active"
                    break;
                default:
                    allNOrComplete = false;
                    break;
            }
        }

        // Priority: ELIMINATED > ACTIVE/NO_STAGES > COMPLETED
        if (hasEliminated) {
            return "ELIMINATED";
        }
        if (hasUpcoming || hasSubmitted) {
            return "ACTIVE";
        }
        if (allNOrComplete) {
            return "COMPLETED";
        }
        return "ACTIVE";  // default fallback
    }

    /**
     * Check if reminders should be suppressed for a given stage.
     * Reminders are suppressed if any earlier stage in the hackathon is ELIMINATED.
     *
     * @param hackathonId the hackathon containing the stages
     * @param stageOrder the stage order to check
     * @return true if reminders should be suppressed (blocked by elimination)
     */
    public static boolean areRemindersSuppressed(int hackathonId, int stageOrder) {
        List<Stage> stages = StageDAO.getByHackathonId(hackathonId);
        for (Stage stage : stages) {
            if (stage.getStageOrder() < stageOrder && stage.getStatus() == StageStatus.ELIMINATED) {
                return true;  // Elimination blocks reminders for later stages
            }
        }
        return false;
    }
}