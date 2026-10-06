package hacktrack.ai;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Outcome of applying a reviewed AI schedule to the existing HackTrack stages.
 */
public class ScheduleApplyResult {

    private int hackathonId;
    private int created;
    private int updated;
    private int unchanged;
    private int skipped;
    private final List<Outcome> outcomes = new ArrayList<>();
    private final List<String> warnings = new ArrayList<>();

    public int getHackathonId() {
        return hackathonId;
    }

    public void setHackathonId(int hackathonId) {
        this.hackathonId = hackathonId;
    }

    public int getCreated() {
        return created;
    }

    public void setCreated(int created) {
        this.created = created;
    }

    public int getUpdated() {
        return updated;
    }

    public void setUpdated(int updated) {
        this.updated = updated;
    }

    public int getUnchanged() {
        return unchanged;
    }

    public void setUnchanged(int unchanged) {
        this.unchanged = unchanged;
    }

    public int getSkipped() {
        return skipped;
    }

    public void setSkipped(int skipped) {
        this.skipped = skipped;
    }

    public List<Outcome> getOutcomes() {
        return outcomes;
    }

    public List<String> getWarnings() {
        return warnings;
    }

    /**
     * What happened to a single stage.
     */
    public static class Outcome {
        private String stageName;
        private LocalDate date;
        private String action;
        private String message;
        private Integer stageId;

        public String getStageName() {
            return stageName;
        }

        public void setStageName(String stageName) {
            this.stageName = stageName;
        }

        public LocalDate getDate() {
            return date;
        }

        public void setDate(LocalDate date) {
            this.date = date;
        }

        /** CREATE, UPDATE, UNCHANGED or SKIP. */
        public String getAction() {
            return action;
        }

        public void setAction(String action) {
            this.action = action;
        }

        public String getMessage() {
            return message;
        }

        public void setMessage(String message) {
            this.message = message;
        }

        public Integer getStageId() {
            return stageId;
        }

        public void setStageId(Integer stageId) {
            this.stageId = stageId;
        }
    }
}
