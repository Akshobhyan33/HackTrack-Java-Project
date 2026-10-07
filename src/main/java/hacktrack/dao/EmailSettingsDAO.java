package hacktrack.dao;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * Per-user key/value settings (currently the reminder recipient email).
 * Every row belongs to a user id, so one account never sees another
 * account's recipient address.
 */
public class EmailSettingsDAO {

    public static String getRecipientEmail(int userId) {
        String sql = "SELECT value FROM email_settings WHERE key = 'recipient_email' AND userId = ?";
        try (Connection conn = Database.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setInt(1, userId);
            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) return rs.getString("value");
            }
        } catch (SQLException e) {
            System.err.println("Error getting recipient email: " + e.getMessage());
        }
        return null;
    }

    public static boolean setRecipientEmail(int userId, String email) {
        String sql = "INSERT INTO email_settings (userId, key, value) VALUES (?, 'recipient_email', ?) "
                + "ON CONFLICT(userId, key) DO UPDATE SET value = excluded.value";
        try (Connection conn = Database.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setInt(1, userId);
            pstmt.setString(2, email);
            return pstmt.executeUpdate() > 0;
        } catch (SQLException e) {
            System.err.println("Error setting recipient email: " + e.getMessage());
        }
        return false;
    }
}
