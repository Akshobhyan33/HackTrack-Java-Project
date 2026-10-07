package hacktrack.dao;

import hacktrack.security.PasswordHasher;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

public class Database {
    private static final String URL = "jdbc:sqlite:" + resolveDbPath();
    private static boolean initialized = false;

    /**
     * Account that owns any rows that existed before authentication was added.
     * Its password is random (account locked) unless HACKTRACK_LEGACY_PASSWORD
     * is set, in which case that password is used/kept in sync so the old data
     * stays reachable to the operator.
     */
    private static final String LEGACY_EMAIL = "legacy@hacktrack.local";

    /**
     * SQLite file location. Resolution order:
     * 1. hacktrack.db.path system property (used by tests)
     * 2. HACKTRACK_DB_PATH environment variable, e.g. /data/hacktrack.db in Docker
     * 3. hacktrack.db (local development)
     */
    private static String resolveDbPath() {
        String path = System.getProperty("hacktrack.db.path");
        if (path == null || path.isBlank()) {
            path = System.getenv("HACKTRACK_DB_PATH");
        }
        return (path == null || path.isBlank()) ? "hacktrack.db" : path.trim();
    }

    public static synchronized void initialize() {
        if (initialized) return;
        try {
            Class.forName("org.sqlite.JDBC");
            try (Connection conn = DriverManager.getConnection(URL)) {
                try (Statement stmt = conn.createStatement()) {
                    stmt.execute("PRAGMA foreign_keys = ON");
                    createTables(stmt);
                }
                migrate(conn);
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
            CREATE TABLE IF NOT EXISTS users (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                name TEXT NOT NULL,
                email TEXT NOT NULL UNIQUE,
                passwordHash TEXT NOT NULL,
                createdAt TIMESTAMP DEFAULT CURRENT_TIMESTAMP
            )
        """);

        stmt.execute("""
            CREATE TABLE IF NOT EXISTS hackathons (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                name TEXT NOT NULL,
                websiteUrl TEXT,
                isStarred INTEGER DEFAULT 0,
                createdAt TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                ownerId INTEGER REFERENCES users(id)
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
                completedAt TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                FOREIGN KEY (hackathonId) REFERENCES hackathons(id) ON DELETE CASCADE
            )
        """);

        stmt.execute("CREATE INDEX IF NOT EXISTS idx_stages_hackathon ON stages(hackathonId)");
        stmt.execute("CREATE INDEX IF NOT EXISTS idx_stages_status ON stages(status)");
        stmt.execute("CREATE INDEX IF NOT EXISTS idx_stages_order ON stages(stageOrder)");
        stmt.execute("CREATE INDEX IF NOT EXISTS idx_reminder_hackathon_stage ON reminder_history(hackathonId, stageId)");

        stmt.execute("""
            CREATE TABLE IF NOT EXISTS email_settings (
                userId INTEGER NOT NULL,
                key TEXT NOT NULL,
                value TEXT NOT NULL,
                PRIMARY KEY (userId, key)
            )
        """);
    }

    /**
     * Adds the authentication columns to databases created before login
     * existed. Nothing is deleted: pre-login hackathons/stages/history are
     * adopted by a legacy user, and the old single recipient email moves to
     * that same account.
     */
    private static void migrate(Connection conn) throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            if (!columnExists(conn, "hackathons", "ownerId")) {
                stmt.execute("ALTER TABLE hackathons ADD COLUMN ownerId INTEGER REFERENCES users(id)");
            }
            // Created here (not in createTables) because on pre-auth databases
            // the ownerId column does not exist until the ALTER above ran.
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_hackathons_owner ON hackathons(ownerId)");
        }

        int legacyId = -1;
        int ownerless = 0;
        try (PreparedStatement ps = conn.prepareStatement("SELECT COUNT(*) FROM hackathons WHERE ownerId IS NULL");
             ResultSet rs = ps.executeQuery()) {
            if (rs.next()) ownerless = rs.getInt(1);
        }
        if (ownerless > 0) {
            legacyId = ensureLegacyUser(conn);
            try (PreparedStatement ps = conn.prepareStatement("UPDATE hackathons SET ownerId = ? WHERE ownerId IS NULL")) {
                ps.setInt(1, legacyId);
                ps.executeUpdate();
            }
            System.out.println("Database: assigned " + ownerless + " pre-login hackathon(s) to the legacy user.");
        }

        if (!columnExists(conn, "email_settings", "userId")) {
            int targetUserId = legacyId > 0 ? legacyId : 0;
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("CREATE TABLE email_settings_migrated ("
                        + "userId INTEGER NOT NULL, key TEXT NOT NULL, value TEXT NOT NULL, "
                        + "PRIMARY KEY (userId, key))");
                try (PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO email_settings_migrated (userId, key, value) SELECT ?, key, value FROM email_settings")) {
                    ps.setInt(1, targetUserId);
                    ps.executeUpdate();
                }
                stmt.execute("DROP TABLE email_settings");
                stmt.execute("ALTER TABLE email_settings_migrated RENAME TO email_settings");
            }
            System.out.println("Database: email settings are now stored per user.");
        }
    }

    private static int ensureLegacyUser(Connection conn) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT id, passwordHash FROM users WHERE email = ?")) {
            ps.setString(1, LEGACY_EMAIL);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    int id = rs.getInt("id");
                    String envPassword = System.getenv("HACKTRACK_LEGACY_PASSWORD");
                    if (envPassword != null && !envPassword.isBlank()
                            && !PasswordHasher.verify(envPassword, rs.getString("passwordHash"))) {
                        try (PreparedStatement update = conn.prepareStatement(
                                "UPDATE users SET passwordHash = ? WHERE id = ?")) {
                            update.setString(1, PasswordHasher.hash(envPassword));
                            update.setInt(2, id);
                            update.executeUpdate();
                        }
                    }
                    return id;
                }
            }
        }

        String envPassword = System.getenv("HACKTRACK_LEGACY_PASSWORD");
        String initialPassword = (envPassword != null && !envPassword.isBlank())
                ? envPassword
                : UUID.randomUUID().toString();

        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO users (name, email, passwordHash, createdAt) VALUES (?, ?, ?, ?)",
                Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, "Legacy User");
            ps.setString(2, LEGACY_EMAIL);
            ps.setString(3, PasswordHasher.hash(initialPassword));
            ps.setString(4, java.time.LocalDateTime.now().toString());
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                if (rs.next()) return rs.getInt(1);
            }
        }
        throw new SQLException("Could not create the legacy user");
    }

    private static boolean columnExists(Connection conn, String table, String column) throws SQLException {
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("PRAGMA table_info(" + table + ")")) {
            while (rs.next()) {
                if (column.equalsIgnoreCase(rs.getString("name"))) return true;
            }
        }
        return false;
    }
}
