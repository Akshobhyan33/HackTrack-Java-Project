package hacktrack.dao;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

public class EmailSettingsDAO {

    public static String getRecipientEmail() {
        String sql = "SELECT value FROM email_settings WHERE key = 'recipient_email'";
        try (Connection conn = Database.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) return rs.getString("value");
            }
        } catch (SQLException e) {
            System.err.println("Error getting recipient email: " + e.getMessage());
        }
        return null;
    }

    public static boolean setRecipientEmail(String email) {
        String sql = "INSERT OR REPLACE INTO email_settings (key, value) VALUES ('recipient_email', ?)";
        try (Connection conn = Database.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, email);
            return pstmt.executeUpdate() > 0;
        } catch (SQLException e) {
            System.err.println("Error setting recipient email: " + e.getMessage());
        }
        return false;
    }
}
