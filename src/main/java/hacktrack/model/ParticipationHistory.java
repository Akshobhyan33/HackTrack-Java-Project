package hacktrack.model;

import java.time.LocalDateTime;

public class ParticipationHistory {
    private int id;
    private int hackathonId;
    private String hackathonName;
    private String finalOutcome;
    private LocalDateTime completedAt;

    public ParticipationHistory() {}

    public ParticipationHistory(int id, int hackathonId, String hackathonName, String finalOutcome, LocalDateTime completedAt) {
        this.id = id;
        this.hackathonId = hackathonId;
        this.hackathonName = hackathonName;
        this.finalOutcome = finalOutcome;
        this.completedAt = completedAt;
    }

    public int getId() { return id; }
    public void setId(int id) { this.id = id; }

    public int getHackathonId() { return hackathonId; }
    public void setHackathonId(int hackathonId) { this.hackathonId = hackathonId; }

    public String getHackathonName() { return hackathonName; }
    public void setHackathonName(String hackathonName) { this.hackathonName = hackathonName; }

    public String getFinalOutcome() { return finalOutcome; }
    public void setFinalOutcome(String finalOutcome) { this.finalOutcome = finalOutcome; }

    public LocalDateTime getCompletedAt() { return completedAt; }
    public void setCompletedAt(LocalDateTime completedAt) { this.completedAt = completedAt; }
}
