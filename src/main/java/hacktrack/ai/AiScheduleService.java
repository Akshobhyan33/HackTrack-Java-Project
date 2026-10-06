package hacktrack.ai;

import hacktrack.dao.HackathonDAO;
import hacktrack.dao.StageDAO;
import hacktrack.model.Hackathon;
import hacktrack.model.Stage;
import hacktrack.status.StageStatus;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Orchestrates the AI schedule feature.
 *
 * Flow: website fetch → AI extraction → mapping onto existing HackTrack stages →
 * preview for the user → explicit apply through the existing {@link StageDAO}.
 *
 * Nothing is ever written to the database during the extraction step, and manual
 * stage entry is untouched.
 */
@Service
public class AiScheduleService {

    private final AiScheduleExtractor extractor;

    public AiScheduleService(AiScheduleExtractor extractor) {
        this.extractor = extractor;
    }

    public boolean isConfigured() {
        return extractor.isConfigured();
    }

    /**
     * Runs the extraction and describes what applying it would change.
     * Purely read-only: no database writes happen here.
     *
     * @param hackathonId existing hackathon the schedule belongs to
     * @param nameOverride optional name typed by the user, falls back to the stored name
     * @param urlOverride  optional URL typed by the user, falls back to the stored URL
     */
    public SchedulePreviewResult preview(int hackathonId, String nameOverride, String urlOverride) {
        Hackathon hackathon = HackathonDAO.getById(hackathonId);
        if (hackathon == null) {
            throw new IllegalArgumentException("Hackathon not found.");
        }

        String name = firstNonBlank(nameOverride, hackathon.getName());
        String url = firstNonBlank(urlOverride, hackathon.getWebsiteUrl());
        if (url == null || url.isBlank()) {
            throw new AiScheduleException(AiScheduleException.Code.INVALID_URL,
                    "This hackathon has no website URL. Edit the hackathon and add its official URL first.");
        }

        ExtractedSchedule extracted = extractor.extract(name, url);

        List<Stage> existing = StageDAO.getByHackathonId(hackathonId);
        List<StagePreview> previews = new ArrayList<>();
        for (ExtractedStage stage : extracted.getStages()) {
            previews.add(buildPreview(stage, existing));
        }

        SchedulePreviewResult result = new SchedulePreviewResult();
        result.setHackathonId(hackathonId);
        result.setHackathonName(extracted.getHackathonName());
        result.setWebsiteUrl(extracted.getWebsiteUrl());
        result.setStages(previews);
        result.setPagesRead(extracted.getPagesRead());
        result.setNotes(extracted.getNotes());
        return result;
    }

    private StagePreview buildPreview(ExtractedStage stage, List<Stage> existing) {
        StagePreview preview = new StagePreview();
        preview.setReportedName(stage.getReportedName());
        preview.setCanonicalName(stage.getCanonicalName());
        preview.setRecognized(stage.isRecognized());
        preview.setDate(stage.getDate());
        preview.setDescription(stage.getDescription());
        preview.setSkipReason(stage.getSkipReason());

        Stage match = findExistingStage(stage, existing);

        if (stage.getDate() == null) {
            preview.setAction(StagePreview.Action.SKIP);
            preview.setSelected(false);
        } else if (!stage.isRecognized()) {
            preview.setAction(StagePreview.Action.SKIP);
            preview.setSelected(false);
        } else if (match == null) {
            preview.setAction(StagePreview.Action.CREATE);
            preview.setSelected(true);
        } else if (match.getDeadline().equals(stage.getDate())) {
            preview.setAction(StagePreview.Action.UNCHANGED);
            preview.setSelected(true);
            attachExisting(preview, match);
        } else {
            preview.setAction(StagePreview.Action.UPDATE);
            preview.setSelected(false);
            attachExisting(preview, match);
        }
        if (match != null && preview.getAction() == StagePreview.Action.CREATE) {
            attachExisting(preview, match);
        }
        return preview;
    }

    private static void attachExisting(StagePreview preview, Stage match) {
        preview.setExistingStageId(match.getId());
        preview.setExistingStageName(match.getName());
        preview.setExistingDeadline(match.getDeadline());
        preview.setExistingStatus(match.getStatus() != null ? match.getStatus().name() : StageStatus.UPCOMING.name());
    }

    /**
     * Writes the user-approved subset of an AI schedule into the existing
     * stages table.
     *
     * Only deadline values are touched on stages that already exist; statuses,
     * orders, reminder offsets and every other hackathon row are left alone.
     *
     * @param overwriteExisting required before an existing deadline may be replaced
     * @param allowCustomStages required before an unrecognised stage name may be inserted
     */
    public ScheduleApplyResult apply(int hackathonId, String nameOverride, String urlOverride,
                                     List<StageSelection> selections, boolean overwriteExisting,
                                     boolean allowCustomStages) {
        Hackathon hackathon = HackathonDAO.getById(hackathonId);
        if (hackathon == null) {
            throw new IllegalArgumentException("Hackathon not found.");
        }
        String resolvedName = firstNonBlank(nameOverride, hackathon.getName());
        String resolvedUrl = firstNonBlank(urlOverride, hackathon.getWebsiteUrl());

        ScheduleApplyResult result = new ScheduleApplyResult();
        result.setHackathonId(hackathonId);
        if (selections == null || selections.isEmpty()) {
            result.getWarnings().add("No stages were selected, so nothing was changed.");
            return result;
        }

        List<Stage> existing = StageDAO.getByHackathonId(hackathonId);
        int nextOrder = existing.isEmpty()
                ? 1
                : existing.get(existing.size() - 1).getStageOrder() + 1;

        for (StageSelection selection : selections) {
            ScheduleApplyResult.Outcome outcome = new ScheduleApplyResult.Outcome();
            String reportedName = selection.getStageName();
            outcome.setStageName(reportedName);

            if (reportedName == null || reportedName.isBlank()) {
                outcome.setAction("SKIP");
                outcome.setMessage("Skipped: no stage name.");
                result.getOutcomes().add(outcome);
                result.setSkipped(result.getSkipped() + 1);
                continue;
            }

            String canonical = StageCatalog.match(reportedName, selection.getDescription());
            LocalDate date = AiDateParser.parse(selection.getDate());
            outcome.setDate(date);

            if (date == null) {
                outcome.setAction("SKIP");
                outcome.setMessage("Skipped \"" + reportedName.trim() + "\": no reliable date was provided.");
                result.getOutcomes().add(outcome);
                result.setSkipped(result.getSkipped() + 1);
                continue;
            }

            Stage match = canonical != null
                    ? findExistingByCanonical(canonical, existing)
                    : findExistingByExactName(reportedName, existing);

            if (match != null) {
                outcome.setStageId(match.getId());
                if (match.getDeadline().equals(date)) {
                    outcome.setAction("UNCHANGED");
                    outcome.setMessage(match.getName() + " already has " + date + ".");
                    result.setUnchanged(result.getUnchanged() + 1);
                } else if (!overwriteExisting) {
                    outcome.setAction("SKIP");
                    outcome.setMessage("Kept your existing deadline " + match.getDeadline()
                            + " for \"" + match.getName() + "\". Tick 'replace existing dates' to change it to " + date + ".");
                    result.setSkipped(result.getSkipped() + 1);
                } else {
                    LocalDate previous = match.getDeadline();
                    match.setDeadline(date);
                    if (StageDAO.update(match)) {
                        outcome.setAction("UPDATE");
                        outcome.setMessage("Updated \"" + match.getName() + "\" from " + previous + " to " + date + ".");
                        result.setUpdated(result.getUpdated() + 1);
                    } else {
                        outcome.setAction("SKIP");
                        outcome.setMessage("Could not update \"" + match.getName() + "\".");
                        result.getWarnings().add("Database rejected the update for \"" + match.getName() + "\".");
                        result.setSkipped(result.getSkipped() + 1);
                    }
                }
                result.getOutcomes().add(outcome);
                continue;
            }

            if (canonical == null && !allowCustomStages) {
                outcome.setAction("SKIP");
                outcome.setMessage("Skipped \"" + reportedName.trim()
                        + "\": it is not one of the existing HackTrack stages. Add it manually, or tick 'allow custom stages'.");
                result.getOutcomes().add(outcome);
                result.setSkipped(result.getSkipped() + 1);
                continue;
            }

            String stageName = canonical != null ? canonical : reportedName.trim();
            if (stageName.length() > 80) {
                stageName = stageName.substring(0, 80);
            }
            Stage created = new Stage(0, hackathonId, nextOrder, stageName, date, StageStatus.UPCOMING, 3);
            int newId = StageDAO.insert(created);
            if (newId <= 0) {
                outcome.setAction("SKIP");
                outcome.setMessage("Could not save \"" + stageName + "\".");
                result.getWarnings().add("Database rejected the insert for \"" + stageName + "\".");
                result.setSkipped(result.getSkipped() + 1);
            } else {
                existing.add(created);
                created.setId(newId);
                nextOrder++;
                outcome.setAction("CREATE");
                outcome.setStageId(newId);
                outcome.setMessage("Added \"" + stageName + "\" with deadline " + date + ".");
                result.setCreated(result.getCreated() + 1);
            }
            result.getOutcomes().add(outcome);
        }

        return result;
    }

    // ── Matching helpers ──

    private static Stage findExistingStage(ExtractedStage extracted, List<Stage> existing) {
        if (extracted.isRecognized()) {
            Stage byCanonical = findExistingByCanonical(extracted.getCanonicalName(), existing);
            if (byCanonical != null) {
                return byCanonical;
            }
        }
        return findExistingByExactName(extracted.getReportedName(), existing);
    }

    private static Stage findExistingByCanonical(String canonicalName, List<Stage> existing) {
        if (canonicalName == null) {
            return null;
        }
        for (Stage stage : existing) {
            if (canonicalName.equals(StageCatalog.match(stage.getName(), null))) {
                return stage;
            }
        }
        return null;
    }

    private static Stage findExistingByExactName(String name, List<Stage> existing) {
        String normalized = StageCatalog.normalize(name);
        for (Stage stage : existing) {
            if (StageCatalog.normalize(stage.getName()).equals(normalized)) {
                return stage;
            }
        }
        return null;
    }

    private static String firstNonBlank(String preferred, String fallback) {
        if (preferred != null && !preferred.isBlank()) {
            return preferred.trim();
        }
        return fallback == null ? null : fallback.trim();
    }

    /**
     * One user-approved stage, as submitted from the review screen.
     */
    public static class StageSelection {
        private String stageName;
        private String date;
        private String description;

        public String getStageName() {
            return stageName;
        }

        public void setStageName(String stageName) {
            this.stageName = stageName;
        }

        public String getDate() {
            return date;
        }

        public void setDate(String date) {
            this.date = date;
        }

        public String getDescription() {
            return description;
        }

        public void setDescription(String description) {
            this.description = description;
        }
    }
}
