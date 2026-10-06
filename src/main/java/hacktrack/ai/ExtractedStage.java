package hacktrack.ai;

import java.time.LocalDate;

/**
 * One stage reported by the AI, after parsing but before user confirmation.
 *
 * Nothing here is persisted: the frontend shows this, the user chooses what to
 * keep, and only then is anything written to the existing {@code stages} table.
 */
public class ExtractedStage {

    private String reportedName;
    private LocalDate date;
    private String rawDate;
    private String description;
    private String canonicalName;
    private boolean recognized;
    private int order;
    private boolean selected = true;
    private String skipReason;

    public String getReportedName() {
        return reportedName;
    }

    public void setReportedName(String reportedName) {
        this.reportedName = reportedName;
    }

    public LocalDate getDate() {
        return date;
    }

    public void setDate(LocalDate date) {
        this.date = date;
    }

    /** The date exactly as the model produced it, useful for display/debugging. */
    public String getRawDate() {
        return rawDate;
    }

    public void setRawDate(String rawDate) {
        this.rawDate = rawDate;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    /** Existing HackTrack stage name this maps to, null when unrecognised. */
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

    /** Lifecycle position used for display ordering. */
    public int getOrder() {
        return order;
    }

    public void setOrder(int order) {
        this.order = order;
    }

    /** Default selection state shown in the frontend (always false for unusable entries). */
    public boolean isSelected() {
        return selected;
    }

    public void setSelected(boolean selected) {
        this.selected = selected;
    }

    /** Why this entry cannot be applied (missing date, custom stage, ...). */
    public String getSkipReason() {
        return skipReason;
    }

    public void setSkipReason(String skipReason) {
        this.skipReason = skipReason;
    }
}
