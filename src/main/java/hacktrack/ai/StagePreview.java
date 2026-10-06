package hacktrack.ai;

import java.time.LocalDate;

/**
 * An extracted stage paired with what applying it would do to the existing
 * HackTrack database. Produced by the preview call so the user can see exactly
 * which values change before confirming.
 */
public class StagePreview {

    /** What applying this entry would do. */
    public enum Action {
        /** No stage with this name exists yet - one would be added. */
        CREATE,
        /** An existing stage would get a new deadline. */
        UPDATE,
        /** An existing stage already has this exact deadline. */
        UNCHANGED,
        /** Cannot be applied (no reliable date, or not an existing stage). */
        SKIP
    }

    private String reportedName;
    private String canonicalName;
    private boolean recognized;
    private LocalDate date;
    private String description;
    private String skipReason;
    private boolean selected;
    private Action action = Action.SKIP;

    private Integer existingStageId;
    private String existingStageName;
    private LocalDate existingDeadline;
    private String existingStatus;

    public String getReportedName() {
        return reportedName;
    }

    public void setReportedName(String reportedName) {
        this.reportedName = reportedName;
    }

    public String getCanonicalName() {
        return canonicalName;
    }

    public void setCanonicalName(String canonicalName) {
        this.canonicalName = canonicalName;
    }

    public boolean isRecognized() {
        return recognized;
    }

    public void setRecognized(boolean recognized) {
        this.recognized = recognized;
    }

    public LocalDate getDate() {
        return date;
    }

    public void setDate(LocalDate date) {
        this.date = date;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getSkipReason() {
        return skipReason;
    }

    public void setSkipReason(String skipReason) {
        this.skipReason = skipReason;
    }

    /** Pre-ticked in the UI. False whenever {@link #getAction()} is SKIP. */
    public boolean isSelected() {
        return selected;
    }

    public void setSelected(boolean selected) {
        this.selected = selected;
    }

    public Action getAction() {
        return action;
    }

    public void setAction(Action action) {
        this.action = action;
    }

    public Integer getExistingStageId() {
        return existingStageId;
    }

    public void setExistingStageId(Integer existingStageId) {
        this.existingStageId = existingStageId;
    }

    public String getExistingStageName() {
        return existingStageName;
    }

    public void setExistingStageName(String existingStageName) {
        this.existingStageName = existingStageName;
    }

    public LocalDate getExistingDeadline() {
        return existingDeadline;
    }

    public void setExistingDeadline(LocalDate existingDeadline) {
        this.existingDeadline = existingDeadline;
    }

    public String getExistingStatus() {
        return existingStatus;
    }

    public void setExistingStatus(String existingStatus) {
        this.existingStatus = existingStatus;
    }
}
