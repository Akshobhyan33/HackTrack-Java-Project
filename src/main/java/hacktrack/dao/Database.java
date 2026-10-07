package hacktrack.dao;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

public class Database {
    private static final String URL = "jdbc:sqlite:" + resolveDbPath();
    private static boolean initialized = false;

    /**
     * SQLite file location. Defaults to hacktrack.db (local development) and can be
     * overridden with the HACKTRACK_DB_PATH environment variable, e.g.
     * HACKTRACK_DB_PATH=/data/hacktrack.db when running in Docker.
     */
    private static String resolveDbPath() {
        String path = System.getenv("HACKTRACK_DB_PATH");
        return (path == null || path.isBlank()) ? "hacktrack.db" : path.trim();
    }

    public static synchronized void initialize() {
        if (initialized) return;
        try {
            Class.forName("org.sqlite.JDBC");
            try (Connection conn = DriverManager.getConnection(URL);
                 Statement stmt = conn.createStatement()) {
                stmt.execute("PRAGMA foreign_keys = ON");
                createTables(stmt);
            }
            initialized = true;
            System.out.println("Database initialized successfully.");
        } catch (Exception e) {
            System.err.println("Error initializing database: " + e.getMessage());
            e.printStackTrace();
        }
    }

    public static Connection getConnection() throws SQLException {
        if (!initialized) {
            initialize();
        }
        Connection conn = DriverManager.getConnection(URL);
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("PRAGMA foreign_keys = ON");
        }
        return conn;
    }

    private static void createTables(Statement stmt) throws SQLException {
        stmt.execute("""
            CREATE TABLE IF NOT EXISTS hackathons (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                name TEXT NOT NULL,
                websiteUrl TEXT,
                isStarred INTEGER DEFAULT 0,
                createdAt TIMESTAMP DEFAULT CURRENT_TIMESTAMP
            )
        """);

        stmt.execute("""
            CREATE TABLE IF NOT EXISTS stages (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                hackathonId INTEGER NOT NULL,
                stageOrder INTEGER NOT NULL,
                name TEXT NOT NULL,
                deadline DATE NOT NULL,
                status TEXT NOT NULL DEFAULT 'UPCOMING',
                reminderOffsetDays INTEGER DEFAULT 3,
                FOREIGN KEY (hackathonId) REFERENCES hackathons(id) ON DELETE CASCADE
            )
        """);

        stmt.execute("""
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
            )
        """);

        stmt.execute("""
            CREATE TABLE IF NOT EXISTS participation_history (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                hackathonId INTEGER NOT NULL,
                finalOutcome TEXT NOT NULL,
                completedAt TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                FOREIGN KEY (hackathonId) REFERENCES hackathons(id) ON DELETE CASCADE
            )
        """);

        stmt.execute("CREATE INDEX IF NOT EXISTS idx_stages_hackathon ON stages(hackathonId)");
        stmt.execute("CREATE INDEX IF NOT EXISTS idx_stages_status ON stages(status)");
        stmt.execute("CREATE INDEX IF NOT EXISTS idx_stages_order ON stages(stageOrder)");
        stmt.execute("CREATE INDEX IF NOT EXISTS idx_reminder_hackathon_stage ON reminder_history(hackathonId, stageId)");

        stmt.execute("""
            CREATE TABLE IF NOT EXISTS email_settings (
                key TEXT PRIMARY KEY,
                value TEXT NOT NULL
            )
        """);
    }
}
