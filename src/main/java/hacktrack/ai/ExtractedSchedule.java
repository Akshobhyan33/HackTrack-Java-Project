package hacktrack.ai;

import java.util.ArrayList;
import java.util.List;

/**
 * Result of one AI schedule extraction run, shown to the user for review.
 */
public class ExtractedSchedule {

    private String hackathonName;
    private String websiteUrl;
    private final List<ExtractedStage> stages = new ArrayList<>();
    private final List<String> pagesRead = new ArrayList<>();
    private final List<String> notes = new ArrayList<>();

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

    public List<ExtractedStage> getStages() {
        return stages;
    }

    /** URLs that were actually read, so the user knows what the model saw. */
    public List<String> getPagesRead() {
        return pagesRead;
    }

    /** Non-fatal observations, e.g. "skipped a link that could not be reached". */
    public List<String> getNotes() {
        return notes;
    }

    /** Number of entries that have a resolvable date. */
    public int getUsableCount() {
        return (int) stages.stream().filter(s -> s.getDate() != null).count();
    }

    /** Number of entries that map onto an existing HackTrack stage. */
    public int getRecognizedCount() {
        return (int) stages.stream().filter(ExtractedStage::isRecognized).count();
    }
}
