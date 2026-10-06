package hacktrack.status;

/**
 * Stage status for HackTrack.
 * Valid values and their meaning:
 * - UPCOMING: Stage has not been reached yet
 * - SUBMITTED: User has submitted required material for this stage
 * - QUALIFIED: User qualified/advanced past this stage
 * - ELIMINATED: User was eliminated/not selected after this stage
 * - COMPLETED: Participation naturally finished at this stage
 * - NOT_APPLICABLE: This stage does not apply to this hackathon structure
 *   Setting one stage to NOT_APPLICABLE does NOT automatically invalidate later stages.
 */
public enum StageStatus {
    UPCOMING,
    SUBMITTED,
    QUALIFIED,
    ELIMINATED,
    COMPLETED,
    NOT_APPLICABLE;

    @Override
    public String toString() {
        return switch (this) {
            case UPCOMING -> "Upcoming";
            case SUBMITTED -> "Submitted";
            case QUALIFIED -> "Qualified";
            case ELIMINATED -> "Eliminated";
            case COMPLETED -> "Completed";
            case NOT_APPLICABLE -> "N/A";
        };
    }
}