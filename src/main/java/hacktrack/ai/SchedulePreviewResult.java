package hacktrack.ai;

import java.util.ArrayList;
import java.util.List;

/**
 * Read-only preview of an AI extraction, returned to the frontend for review.
 * Nothing in this object has been written to the database.
 */
public class SchedulePreviewResult {

    private int hackathonId;
    private String hackathonName;
    private String websiteUrl;
    private List<StagePreview> stages = new ArrayList<>();
    private List<String> pagesRead = new ArrayList<>();
    private List<String> notes = new ArrayList<>();

    public int getHackathonId() {
        return hackathonId;
    }

    public void setHackathonId(int hackathonId) {
        this.hackathonId = hackathonId;
    }

    public String getHackathonName() {
        return hackathonName;
    }

    public void setHackathonName(String hackathonName) {
        this.hackathonName = hackathonName;
    }

    public String getWebsiteUrl() {
        return websiteUrl;
    }

    public void setWebsiteUrl(String websiteUrl) {
        this.websiteUrl = websiteUrl;
    }

    public List<StagePreview> getStages() {
        return stages;
    }

    public void setStages(List<StagePreview> stages) {
        this.stages = stages;
    }

    /** URLs that were read before asking the AI, so the user knows the source. */
    public List<String> getPagesRead() {
        return pagesRead;
    }

    public void setPagesRead(List<String> pagesRead) {
        this.pagesRead = pagesRead;
    }

    public List<String> getNotes() {
        return notes;
    }

    public void setNotes(List<String> notes) {
        this.notes = notes;
    }

    /** True when at least one entry could be saved. */
    public boolean isApplicable() {
        return stages.stream().anyMatch(s -> s.getAction() != StagePreview.Action.SKIP);
    }
}
