# HackTrack — Project Context

## Goal
A personal hackathon tracking and reminder system for college students. Students manually add hackathons, define stages (Registration → PPT Submission → PPT Result → Round 1 → Round 2 → Final), track progress, and receive stage-aware reminders. If eliminated after a stage, reminders for later stages automatically stop.

Students can optionally let the AI read a hackathon's official website and propose the schedule, which they then review before applying it to the normal stage list.

## Tech Stack
- **Backend:** Java 17, Spring Boot 3.2.5, Maven
- **Database:** SQLite via Xerial JDBC 3.45.0.0
- **Frontend:** HTML + CSS + JavaScript (no frameworks)
- **Server:** Embedded Tomcat (bundled with Spring Boot), port 8080

## Architecture
```
Browser (HTML/CSS/JS)
    ↓ HTTP/REST (fetch API)
Spring Boot REST Controller (/api/*)
    ↓
DAO Layer (JDBC)
    ↓
SQLite Database (hacktrack.db)
```

## How to Build & Run
```powershell
# Maven path (not globally installed)
$mvn = "C:\Users\DELL\maven-tmp\apache-maven-3.9.7\bin\mvn.cmd"

# Compile
& $mvn clean compile

# Run (starts Tomcat on port 8080)
& $mvn spring-boot:run

# Then open in browser:
# http://localhost:8080
```

### Deployment Environment Variables

All optional; the defaults keep local development exactly as before.

| Variable | Default | Purpose |
| --- | --- | --- |
| `PORT` | `8080` | HTTP port (`server.port=${PORT:8080}`). |
| `HACKTRACK_DB_PATH` | `hacktrack.db` | SQLite file path (`hacktrack.dao.Database`). The Docker image sets `/data/hacktrack.db`; mount a persistent volume at `/data` — never at `/app`, which holds the JAR. |
| `GMAIL_REDIRECT_URI` | `http://localhost:8080/api/gmail/callback` | Gmail OAuth callback (`hacktrack.service.GmailService`). At deployment set it to the HTTPS callback URL, which must also be authorized in the Google Cloud OAuth client. |
| `GEMINI_API_KEY` | — | Key for the optional AI feature (see below). |

Docker build files (`Dockerfile`, `.dockerignore`) live in the repo root. `config/`
(credentials, Gmail tokens, local properties) is excluded from the build context and
must be provided at runtime via environment variables / mounted volumes.

### AI Schedule Extraction (optional feature)

The AI feature reads a hackathon's official website and suggests stage dates. It is
opt-in and the rest of the app works fully without it.

Set the API key as an environment variable **before** starting the app - it is never
stored in the repository, in SQLite, in the HTML or in the browser:

```powershell
# Temporary, current PowerShell session only
$env:GEMINI_API_KEY = "your-gemini-key-here"
& $mvn spring-boot:run

# Or persist it for your Windows user
[Environment]::SetEnvironmentVariable("GEMINI_API_KEY", "your-gemini-key-here", "User")
```

Get the key from Google AI Studio (https://aistudio.google.com/apikey). A JSON
credential file is not required and is not used.

Alternatively create `config/application-local.properties` (git-ignored) with
`hacktrack.ai.api-key=your-gemini-key-here`.

Optional overrides:

| Variable | Default | Notes |
| --- | --- | --- |
| `GEMINI_MODEL` | `gemini-flash-lite-latest` | Free-tier alias. Use `gemini-flash-latest` or a pinned `gemini-3.8-flash` if you have billing enabled. |
| `GEMINI_BASE_URL` | `https://generativelanguage.googleapis.com/v1beta` | Only change for testing. |
| `GEMINI_MAX_FOLLOW_LINKS` | `2` | Extra same-host pages followed looking for a schedule. |
| `GEMINI_REQUEST_TIMEOUT_SECONDS` | `60` | Per-request timeout. |

The provider is the Google Gemini API (`generateContent`). Gemini is asked for
structured JSON via `responseMimeType` plus `responseSchema`, so replies already match
HackTrack's schedule contract; Markdown fences and alternative key names are still
tolerated before parsing.
## Core Differentiator
**Stage-aware tracking with elimination-aware reminder gating.** Not a generic todo list — the system models the full hackathon lifecycle and only reminds about stages the student can still reach.

## Completed Files (13 Java files + 4 frontend files)

### Entry Point
- `src/main/java/hacktrack/HackTrackApplication.java` — Spring Boot main class (`@SpringBootApplication`). Initializes database and reminder scheduler, then starts Spring context.

### Controller
- `src/main/java/hacktrack/controller/HackathonController.java` — Single `@RestController` at `/api`. Full REST API: hackathon CRUD, stage CRUD, star toggle, next-stage, overall-status, participation history. Handles progression engine dispatch on stage status changes. Auto-records participation history when overall status becomes COMPLETED or ELIMINATED.

### Model
- `src/main/java/hacktrack/model/Hackathon.java` — Plain POJO: id, name, websiteUrl, isStarred, createdAt, statusString, stageCount. No JavaFX properties.
- `src/main/java/hacktrack/model/Stage.java` — id, hackathonId, stageOrder, name, deadline, status (StageStatus enum), reminderOffsetDays. Business methods: `canReceiveReminders()`, `isEliminated()`, `isNotApplicable()`.
- `src/main/java/hacktrack/model/ParticipationHistory.java` — id, hackathonId, hackathonName, finalOutcome, completedAt.

### Enum
- `src/main/java/hacktrack/status/StageStatus.java` — UPCOMING, SUBMITTED, QUALIFIED, ELIMINATED, COMPLETED, NOT_APPLICABLE. Custom `toString()` for display ("Upcoming", "N/A", etc.).

### DAO
- `src/main/java/hacktrack/dao/Database.java` — SQLite connection factory. Each `getConnection()` call creates a new connection and sets `PRAGMA foreign_keys = ON`. Creates 4 tables (hackathons, stages, reminder_history, participation_history) with indexes. All FK constraints use `ON DELETE CASCADE`.
- `src/main/java/hacktrack/dao/HackathonDAO.java` — CRUD for hackathons. `delete()` manually cleans dependent records (reminder_history, participation_history, stages) in a transaction before deleting the hackathon, for compatibility with existing databases.
- `src/main/java/hacktrack/dao/StageDAO.java` — CRUD for stages. Stores `status.name()` (e.g. "UPCOMING"), not `status.toString()` (e.g. "Upcoming"). Reads with `StageStatus.valueOf()`.
- `src/main/java/hacktrack/dao/HistoryDAO.java` — CRUD for participation_history with JOIN to hackathons. Includes `hasHistoryForHackathon()` for duplicate prevention during auto-recording.

### Logic
- `src/main/java/hacktrack/logic/ProgressionEngine.java` — Core business logic. `handleElimination()` cascades later stages to NOT_APPLICABLE. `handleQualification()` sets next stage to UPCOMING. `handleSubmitted()` same. `setManualNotApplicable()` does NOT cascade (intentional). `calculateOverallStatus()` returns ACTIVE/ELIMINATED/COMPLETED/NO_STAGES. `areRemindersSuppressed()` checks if any earlier stage is ELIMINATED.
- `src/main/java/hacktrack/logic/ReminderLogic.java` — 3-day default offset, elimination gating, overdue detection, duplicate prevention (7-day window via SQLite `DATE('now', '-7 days')`), history storage.
- `src/main/java/hacktrack/logic/ReminderScheduler.java` — `ScheduledExecutorService`, checks reminders every minute, listener pattern, daemon thread. Methods: `start()`, `stop()`, `addNotificationListener()`, `removeNotificationListener()`.

### Frontend
- `src/main/resources/static/index.html` — Single-page app. Header with logo/title/stats, search bar, hackathon list, stage panel, participation history section, Gmail panel, modal for add/edit forms, footer. Loads Inter font from Google Fonts.
- `src/main/resources/static/css/style.css` — Custom design with CSS variables, gradient header, responsive breakpoints (768px, 480px), status-specific colors, card shadows, modal animations, mobile-friendly layout. No frameworks.
- `src/main/resources/static/js/app.js` — SPA logic using `fetch()` for all API calls. Functions: load/add/edit/delete hackathons, add/edit/delete stages, toggle star, filter/search, modal management, history display, AI schedule extraction/review/apply. Keyboard shortcut: Escape closes modal.

### Config
- `src/main/resources/application.properties` — `server.port=8080`, `spring.application.name=HackTrack`.
- `pom.xml` — Spring Boot parent 3.2.5, Java 17, spring-boot-starter-web, sqlite-jdbc 3.45.0.0, spring-boot-maven-plugin.

## Database Schema

```sql
-- hackathons
CREATE TABLE IF NOT EXISTS hackathons (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    name TEXT NOT NULL,
    websiteUrl TEXT,
    isStarred INTEGER DEFAULT 0,
    createdAt TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- stages
CREATE TABLE IF NOT EXISTS stages (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    hackathonId INTEGER NOT NULL,
    stageOrder INTEGER NOT NULL,
    name TEXT NOT NULL,
    deadline DATE NOT NULL,
    status TEXT NOT NULL DEFAULT 'UPCOMING',
    reminderOffsetDays INTEGER DEFAULT 3,
    FOREIGN KEY (hackathonId) REFERENCES hackathons(id) ON DELETE CASCADE
);

-- reminder_history
CREATE TABLE IF NOT EXISTS reminder_history (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    hackathonId INTEGER NOT NULL,
    stageId INTEGER NOT NULL,
    offsetDays INTEGER NOT NULL,
    sentAt TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deliveryMethod TEXT NOT NULL,
    success INTEGER NOT NULL DEFAULT 1,
    FOREIGN KEY (hackathonId) REFERENCES hackathons(id) ON DELETE CASCADE,
    FOREIGN KEY (stageId) REFERENCES stages(id) ON DELETE CASCADE
);

-- participation_history
CREATE TABLE IF NOT EXISTS participation_history (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    hackathonId INTEGER NOT NULL,
    finalOutcome TEXT NOT NULL,
    completedAt TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (hackathonId) REFERENCES hackathons(id) ON DELETE CASCADE
);
```

Indexes: `stages(hackathonId)`, `stages(status)`, `stages(stageOrder)`, `reminder_history(hackathonId, stageId)`

**Important:** All foreign keys use `ON DELETE CASCADE`. Additionally, `HackathonDAO.delete()` manually cleans dependent records before deletion for backward compatibility with databases created before the CASCADE was added.

## REST API Endpoints

| Method | Path | Description |
|--------|------|-------------|
| GET | `/api/hackathons` | List all hackathons with stage count + overall status |
| GET | `/api/hackathons/{id}` | Get single hackathon |
| POST | `/api/hackathons` | Create hackathon (body: `{name, websiteUrl}`) |
| PUT | `/api/hackathons/{id}` | Update hackathon |
| DELETE | `/api/hackathons/{id}` | Delete hackathon + all stages + history |
| PUT | `/api/hackathons/{id}/toggle-star` | Toggle star/priority |
| GET | `/api/hackathons/{hid}/stages` | List stages for hackathon |
| POST | `/api/hackathons/{hid}/stages` | Add stage (body: `{name, deadline}`) |
| PUT | `/api/stages/{id}` | Update stage (triggers progression engine) |
| DELETE | `/api/stages/{id}` | Delete stage |
| GET | `/api/hackathons/{hid}/next-stage` | Get next relevant stage |
| GET | `/api/hackathons/{hid}/overall-status` | Get overall status |
| GET | `/api/history` | List participation history |
| POST | `/api/history` | Add history entry |
| DELETE | `/api/history/{id}` | Delete history entry |

**Note:** Participation history is auto-recorded when a hackathon's overall status becomes COMPLETED or ELIMINATED. Duplicate entries are prevented via `hasHistoryForHackathon()` check.

## Key Design Decisions

1. **Spring Boot web app, not JavaFX desktop.** Frontend is pure HTML/CSS/JS served from `static/`. Backend exposes REST API.
2. **Package name**: `hacktrack.status` (NOT `hacktrack.enums`) — `enum` is a Java keyword.
3. **ELIMINATED vs NOT_APPLICABLE**: ELIMINATED blocks later stages (auto-cascades). Manual NOT_APPLICABLE does NOT cascade.
4. **Overall status derived, not stored**: computed from stage statuses (ACTIVE, ELIMINATED, COMPLETED, NO_STAGES).
5. **stageOrder field**: ensures stages always retrieved in correct progression order.
6. **Reminder suppression**: checked per-stage before firing; if any earlier stage is ELIMINATED, skip all later stages.
7. **Database connection**: new connection per DAO call via `DriverManager.getConnection()`. Each connection sets `PRAGMA foreign_keys = ON`.
8. **StageDAO stores enum `.name()`**: `UPCOMING` stored in DB, read back with `StageStatus.valueOf()`. Custom `toString()` only used for display in controller's `stageToMap()`.
9. **HackathonDAO.delete()**: manually deletes from reminder_history, participation_history, stages, then hackathons in a transaction. Ensures compatibility with databases created before ON DELETE CASCADE was added.
10. **Reminder scheduler**: daemon-thread ScheduledExecutorService, checks every minute, listener-based notification to UI.
11. **Single REST controller**: all endpoints in `HackathonController.java`. Uses `Map<String, Object>` for JSON serialization (no DTO layer).
12. **SQLite timestamp parsing**: SQLite stores timestamps as `YYYY-MM-DD HH:MM:SS` (space separator). DAO layer replaces space with `T` before calling `LocalDateTime.parse()`.

## Current File Inventory

```
src/main/java/hacktrack/
├── HackTrackApplication.java        (16 lines)
├── controller/
│   └── HackathonController.java     (302 lines)
├── dao/
│   ├── Database.java                (93 lines)
│   ├── HackathonDAO.java            (153 lines)
│   ├── HistoryDAO.java              (70 lines)
│   └── StageDAO.java                (231 lines)
├── logic/
│   ├── ProgressionEngine.java       (198 lines)
│   ├── ReminderLogic.java           (173 lines)
│   └── ReminderScheduler.java       (72 lines)
├── model/
│   ├── Hackathon.java               (63 lines)
│   ├── ParticipationHistory.java    (36 lines)
│   └── Stage.java                   (82 lines)
└── status/
    └── StageStatus.java             (33 lines)

src/main/resources/
├── application.properties
└── static/
    ├── index.html
    ├── css/
    │   └── style.css
    └── js/
        └── app.js
```

## Project History

The project was originally a **JavaFX desktop application** (with FXML, controllers, JavaFX properties). It was converted to a **Spring Boot web application** in September 2026:

1. Removed all JavaFX dependencies, FXML files, and UI controller classes
2. Added Spring Boot parent + spring-boot-starter-web dependency
3. Created `HackTrackApplication.java` (Spring Boot entry point)
4. Created `HackathonController.java` (REST API)
5. Replaced JavaFX properties (`StringProperty`, `IntegerProperty`) with plain Java fields
6. Fixed `StageDAO` to store `enum.name()` not `enum.toString()` for correct round-tripping
7. Fixed SQLite timestamp parsing (space → T replacement)
8. Fixed MySQL-specific `DATE_SUB()` to SQLite-compatible `DATE('now', '-7 days')` in ReminderLogic
9. Created frontend (HTML + CSS + JavaScript) in `static/`
10. Added `ON DELETE CASCADE` to `reminder_history` and `participation_history` foreign keys
11. Fixed hackathon deletion to manually clean dependent records for backward compatibility
12. Ensured `PRAGMA foreign_keys = ON` is set on every connection (not just during init)
13. Improved UI with CSS variables, gradient header, responsive design, status badges, modal animations
14. Added history auto-recording: participation_history is automatically created when hackathon reaches COMPLETED or ELIMINATED status (via `HistoryDAO.hasHistoryForHackathon()` duplicate check)
