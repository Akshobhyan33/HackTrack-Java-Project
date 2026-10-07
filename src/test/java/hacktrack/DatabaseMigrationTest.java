package hacktrack;

import hacktrack.dao.Database;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that a database created before login existed is migrated safely:
 * no hackathon is deleted or lost, existing rows are adopted by a legacy
 * user, and the old global recipient email moves to that same account.
 */
class DatabaseMigrationTest {

    @Test
    void preAuthDatabaseIsMigratedWithoutLosingData() throws Exception {
        Path dbFile = Files.createTempFile("hacktrack-legacy", ".db");

        // 1. Build a database exactly as the pre-authentication version left it.
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbFile)) {
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("""
                    CREATE TABLE hackathons (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        name TEXT NOT NULL,
                        websiteUrl TEXT,
                        isStarred INTEGER DEFAULT 0,
                        createdAt TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                    )
                """);
                stmt.execute("INSERT INTO hackathons (name, websiteUrl, isStarred, createdAt) "
                        + "VALUES ('SIH Hackathon', 'https://sih.example', 1, '2025-01-01T10:00:00')");
                stmt.execute("INSERT INTO hackathons (name, websiteUrl, isStarred, createdAt) "
                        + "VALUES ('HackTrack Cup', '', 0, '2025-02-01T10:00:00')");
                stmt.execute("""
                    CREATE TABLE stages (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        hackathonId INTEGER NOT NULL,
                        stageOrder INTEGER NOT NULL,
                        name TEXT NOT NULL,
                        deadline DATE NOT NULL,
                        status TEXT NOT NULL DEFAULT 'UPCOMING',
                        reminderOffsetDays INTEGER DEFAULT 3
                    )
                """);
                stmt.execute("INSERT INTO stages (hackathonId, stageOrder, name, deadline, status) "
                        + "VALUES (1, 1, 'Registration', '2025-03-01', 'UPCOMING')");
                stmt.execute("""
                    CREATE TABLE participation_history (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        hackathonId INTEGER NOT NULL,
                        finalOutcome TEXT NOT NULL,
                        completedAt TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                    )
                """);
                stmt.execute("CREATE TABLE email_settings (key TEXT PRIMARY KEY, value TEXT NOT NULL)");
                stmt.execute("INSERT INTO email_settings (key, value) VALUES ('recipient_email', 'old@example.com')");
            }

            // 2. Run the same two steps Database.initialize() performs.
            try (Statement stmt = conn.createStatement()) {
                invokePrivate(Database.class, "createTables", new Class<?>[]{Statement.class}, stmt);
            }
            invokePrivate(Database.class, "migrate", new Class<?>[]{Connection.class}, conn);

            // 3. Nothing was deleted.
            try (Statement stmt = conn.createStatement()) {
                try (ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM hackathons")) {
                    assertTrue(rs.next());
                    assertEquals(2, rs.getInt(1), "pre-login hackathons must be preserved");
                }
                try (ResultSet rs = stmt.executeQuery("SELECT name FROM hackathons ORDER BY id")) {
                    assertTrue(rs.next());
                    assertEquals("SIH Hackathon", rs.getString(1));
                    assertTrue(rs.next());
                    assertEquals("HackTrack Cup", rs.getString(1));
                }
                try (ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM stages")) {
                    assertTrue(rs.next());
                    assertEquals(1, rs.getInt(1), "stages must be preserved");
                }

                // 4. Every hackathon now belongs to the legacy user.
                int legacyId;
                try (ResultSet rs = stmt.executeQuery(
                        "SELECT COUNT(*) FROM hackathons WHERE ownerId IS NULL")) {
                    assertTrue(rs.next());
                    assertEquals(0, rs.getInt(1), "no hackathon may be left ownerless");
                }
                try (ResultSet rs = stmt.executeQuery(
                        "SELECT id, name, email, passwordHash FROM users")) {
                    assertTrue(rs.next(), "legacy user must exist");
                    legacyId = rs.getInt("id");
                    assertEquals("Legacy User", rs.getString("name"));
                    assertEquals("legacy@hacktrack.local", rs.getString("email"));
                    String hash = rs.getString("passwordHash");
                    assertTrue(hash.startsWith("pbkdf2-sha256$"),
                            "legacy password must be stored hashed, never plaintext");
                    assertFalse(rs.next(), "a fresh sign-up flow must not invent extra users here");
                }
                try (ResultSet rs = stmt.executeQuery(
                        "SELECT ownerId FROM hackathons ORDER BY id")) {
                    assertTrue(rs.next());
                    assertEquals(legacyId, rs.getInt("ownerId"));
                    assertTrue(rs.next());
                    assertEquals(legacyId, rs.getInt("ownerId"));
                }

                // 5. The old recipient email moved to that same account.
                try (ResultSet rs = stmt.executeQuery(
                        "SELECT userId, value FROM email_settings WHERE key = 'recipient_email'")) {
                    assertTrue(rs.next(), "recipient email setting must be preserved");
                    assertEquals(legacyId, rs.getInt("userId"));
                    assertEquals("old@example.com", rs.getString("value"));
                }
            }
        }

        // 6. Running the migration a second time is a no-op (idempotent).
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbFile)) {
            try (Statement stmt = conn.createStatement()) {
                invokePrivate(Database.class, "createTables", new Class<?>[]{Statement.class}, stmt);
            }
            invokePrivate(Database.class, "migrate", new Class<?>[]{Connection.class}, conn);
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM hackathons")) {
                assertTrue(rs.next());
                assertEquals(2, rs.getInt(1));
            }
        }
        Files.deleteIfExists(dbFile);
    }

    private static void invokePrivate(Class<?> target, String methodName, Class<?>[] params, Object argument)
            throws Exception {
        Method method = target.getDeclaredMethod(methodName, params);
        method.setAccessible(true);
        method.invoke(null, argument);
    }
}
