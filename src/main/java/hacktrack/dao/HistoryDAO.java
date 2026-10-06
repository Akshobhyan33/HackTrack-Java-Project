package hacktrack.dao;

import hacktrack.model.ParticipationHistory;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

public class HistoryDAO {

    public static int insert(int hackathonId, String finalOutcome) {
        String sql = "INSERT INTO participation_history (hackathonId, finalOutcome) VALUES (?, ?)";
        try (Connection conn = Database.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql, PreparedStatement.RETURN_GENERATED_KEYS)) {
            pstmt.setInt(1, hackathonId);
            pstmt.setString(2, finalOutcome);
            pstmt.executeUpdate();
            try (ResultSet rs = pstmt.getGeneratedKeys()) {
                if (rs.next()) return rs.getInt(1);
            }
        } catch (SQLException e) {
            System.err.println("Error inserting participation history: " + e.getMessage());
        }
        return -1;
    }

    public static List<ParticipationHistory> getAll() {
        List<ParticipationHistory> list = new ArrayList<>();
        String sql = """
            SELECT ph.id, ph.hackathonId, h.name as hackathonName, ph.finalOutcome, ph.completedAt
            FROM participation_history ph
            JOIN hackathons h ON ph.hackathonId = h.id
            ORDER BY ph.completedAt DESC
        """;
        try (Connection conn = Database.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql);
             ResultSet rs = pstmt.executeQuery()) {
            while (rs.next()) {
                ParticipationHistory ph = new ParticipationHistory();
                ph.setId(rs.getInt("id"));
                ph.setHackathonId(rs.getInt("hackathonId"));
                ph.setHackathonName(rs.getString("hackathonName"));
                ph.setFinalOutcome(rs.getString("finalOutcome"));
                String completedAtStr = rs.getString("completedAt");
                if (completedAtStr != null) {
                    ph.setCompletedAt(LocalDateTime.parse(completedAtStr.replace(' ', 'T')));
                }
                list.add(ph);
            }
        } catch (SQLException e) {
            System.err.println("Error getting participation history: " + e.getMessage());
        }
        return list;
    }

    public static boolean hasHistoryForHackathon(int hackathonId) {
        String sql = "SELECT COUNT(*) FROM participation_history WHERE hackathonId = ?";
        try (Connection conn = Database.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setInt(1, hackathonId);
            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) return rs.getInt(1) > 0;
            }
        } catch (SQLException e) {
            System.err.println("Error checking participation history: " + e.getMessage());
        }
        return false;
    }

    public static boolean delete(int id) {
        String sql = "DELETE FROM participation_history WHERE id = ?";
        try (Connection conn = Database.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setInt(1, id);
            return pstmt.executeUpdate() > 0;
        } catch (SQLException e) {
            System.err.println("Error deleting participation history: " + e.getMessage());
        }
        return false;
    }
}
