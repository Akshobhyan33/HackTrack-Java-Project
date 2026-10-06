package hacktrack.model;

import hacktrack.status.StageStatus;
import java.time.LocalDate;

/**
 * Represents a single stage within a hackathon.
 * Each stage has a name, deadline, status, and an order number.
 * Stage order determines the natural progression through the hackathon lifecycle.
 */
public class Stage {
    private int id;
    private int hackathonId;
    private int stageOrder;  // 1 = Registration, 2 = PPT Submission, etc.
    private String name;
    private LocalDate deadline;
    private StageStatus status;
    private int reminderOffsetDays;  // default 3 days

    public Stage() {
        this.id = 0;
        this.hackathonId = 0;
        this.stageOrder = 0;
        this.name = "";
        this.deadline = LocalDate.now();
        this.status = StageStatus.UPCOMING;
        this.reminderOffsetDays = 3;
    }

    public Stage(int id, int hackathonId, int stageOrder, String name, LocalDate deadline,
                 StageStatus status, int reminderOffsetDays) {
        this.id = id;
        this.hackathonId = hackathonId;
        this.stageOrder = stageOrder;
        this.name = name;
        this.deadline = deadline;
        this.status = status;
        this.reminderOffsetDays = reminderOffsetDays;
    }

    // Getters and Setters
    public int getId() { return id; }
    public void setId(int id) { this.id = id; }

    public int getHackathonId() { return hackathonId; }
    public void setHackathonId(int hackathonId) { this.hackathonId = hackathonId; }

    public int getStageOrder() { return stageOrder; }
    public void setStageOrder(int stageOrder) { this.stageOrder = stageOrder; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public LocalDate getDeadline() { return deadline; }
    public void setDeadline(LocalDate deadline) { this.deadline = deadline; }

    public StageStatus getStatus() { return status; }
    public void setStatus(StageStatus status) { this.status = status; }

    public int getReminderOffsetDays() { return reminderOffsetDays; }
    public void setReminderOffsetDays(int reminderOffsetDays) { this.reminderOffsetDays = reminderOffsetDays; }

    // Check if this stage can receive reminders
    public boolean canReceiveReminders() {
        return this.status == StageStatus.UPCOMING;
    }

    // Check if elimination blocks this stage
    public boolean isEliminated() {
        return this.status == StageStatus.ELIMINATED;
    }

    // Check if stage is not applicable
    public boolean isNotApplicable() {
        return this.status == StageStatus.NOT_APPLICABLE;
    }

    @Override
    public String toString() {
        return String.format("%s (Order %d) - %s", name, stageOrder, status);
    }
}