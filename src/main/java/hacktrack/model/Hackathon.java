package hacktrack.model;

import java.time.LocalDateTime;

public class Hackathon {
    private int id;
    private String name;
    private String websiteUrl;
    private boolean isStarred;
    private LocalDateTime createdAt;
    private String statusString;
    private int stageCount;

    public Hackathon() {
        this.id = 0;
        this.name = "";
        this.websiteUrl = "";
        this.isStarred = false;
        this.createdAt = LocalDateTime.now();
        this.statusString = "?";
        this.stageCount = 0;
    }

    public Hackathon(int id, String name, String websiteUrl, boolean isStarred) {
        this.id = id;
        this.name = name;
        this.websiteUrl = websiteUrl;
        this.isStarred = isStarred;
        this.createdAt = LocalDateTime.now();
        this.statusString = "?";
        this.stageCount = 0;
    }

    public int getId() { return id; }
    public void setId(int id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getWebsiteUrl() { return websiteUrl; }
    public void setWebsiteUrl(String websiteUrl) { this.websiteUrl = websiteUrl; }

    public boolean isStarred() { return isStarred; }
    public void setStarred(boolean starred) { isStarred = starred; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public int getStageCount() { return stageCount; }
    public void setStageCount(int count) { this.stageCount = count; }

    public String getStatusString() { return statusString; }
    public void setStatusString(String s) { this.statusString = s; }

    public boolean isActive() {
        return createdAt != null;
    }

    @Override
    public String toString() {
        return name + (isStarred ? " ★" : "");
    }
}
