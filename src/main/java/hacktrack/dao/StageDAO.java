package hacktrack.dao;

import hacktrack.model.Stage;
import hacktrack.status.StageStatus;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Data Access Object for Stage entities.
 * Provides CRUD operations for the stages table.
 */
public class StageDAO {

    // INSERT a new stage
    public static int insert(Stage s) {
        String sql = "INSERT INTO stages (hackathonId, stageOrder, name, deadline, status, reminderOffsetDays) VALUES (?, ?, ?, ?, ?, ?)";
        try (Connection conn = Database.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql, PreparedStatement.RETURN_GENERATED_KEYS)) {

            pstmt.setInt(1, s.getHackathonId());
            pstmt.setInt(2, s.getStageOrder());
            pstmt.setString(3, s.getName());
            pstmt.setString(4, s.getDeadline().toString());
            pstmt.setString(5, s.getStatus().name());
            pstmt.setInt(6, s.getReminderOffsetDays());

            pstmt.executeUpdate();

            // Return the generated key
            try (ResultSet rs = pstmt.getGeneratedKeys()) {
                if (rs.next()) {
                    s.setId(rs.getInt(1));
                    return rs.getInt(1);
                }
            }
        } catch (SQLException e) {
            System.err.println("Error inserting stage: " + e.getMessage());
        }
        return -1;
    }

    // GET a stage by ID
    public static Stage getById(int id) {
        String sql = "SELECT * FROM stages WHERE id = ?";
        try (Connection conn = Database.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setInt(1, id);
            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    Stage s = new Stage();
                    s.setId(rs.getInt("id"));
                    s.setHackathonId(rs.getInt("hackathonId"));
                    s.setStageOrder(rs.getInt("stageOrder"));
                    s.setName(rs.getString("name"));
                    s.setDeadline(LocalDate.parse(rs.getString("deadline")));
                    s.setStatus(StageStatus.valueOf(rs.getString("status")));
                    s.setReminderOffsetDays(rs.getInt("reminderOffsetDays"));
                    return s;
                }
            }
        } catch (SQLException e) {
            System.err.println("Error getting stage: " + e.getMessage());
        }
        return null;
    }

    // GET all stages for a specific hackathon, ordered by stageOrder
    public static List<Stage> getByHackathonId(int hackathonId) {
        List<Stage> list = new ArrayList<>();
        String sql = "SELECT * FROM stages WHERE hackathonId = ? ORDER BY stageOrder ASC";
        try (Connection conn = Database.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setInt(1, hackathonId);
            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    Stage s = new Stage();
                    s.setId(rs.getInt("id"));
                    s.setHackathonId(rs.getInt("hackathonId"));
                    s.setStageOrder(rs.getInt("stageOrder"));
                    s.setName(rs.getString("name"));
                    s.setDeadline(LocalDate.parse(rs.getString("deadline")));
                    s.setStatus(StageStatus.valueOf(rs.getString("status")));
                    s.setReminderOffsetDays(rs.getInt("reminderOffsetDays"));
                    list.add(s);
                }
            }
        } catch (SQLException e) {
            System.err.println("Error getting stages by hackathon: " + e.getMessage());
        }
        return list;
    }

    // GET the next relevant stage (first UPCOMING or SUBMITTED, skipping N/A and ELIMINATED)
    public static Stage getNextRelevantStage(int hackathonId) {
        String sql = "SELECT * FROM stages WHERE hackathonId = ? AND status IN ('UPCOMING', 'SUBMITTED') ORDER BY stageOrder ASC LIMIT 1";
        try (Connection conn = Database.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setInt(1, hackathonId);
            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    Stage s = new Stage();
                    s.setId(rs.getInt("id"));
                    s.setHackathonId(rs.getInt("hackathonId"));
                    s.setStageOrder(rs.getInt("stageOrder"));
                    s.setName(rs.getString("name"));
                    s.setDeadline(LocalDate.parse(rs.getString("deadline")));
                    s.setStatus(StageStatus.valueOf(rs.getString("status")));
                    s.setReminderOffsetDays(rs.getInt("reminderOffsetDays"));
                    return s;
                }
            }
        } catch (SQLException e) {
            System.err.println("Error getting next relevant stage: " + e.getMessage());
        }
        return null;
    }

    // UPDATE an existing stage
    public static boolean update(Stage s) {
        String sql = "UPDATE stages SET hackathonId = ?, stageOrder = ?, name = ?, deadline = ?, status = ?, reminderOffsetDays = ? WHERE id = ?";
        try (Connection conn = Database.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setInt(1, s.getHackathonId());
            pstmt.setInt(2, s.getStageOrder());
            pstmt.setString(3, s.getName());
            pstmt.setString(4, s.getDeadline().toString());
            pstmt.setString(5, s.getStatus().name());
            pstmt.setInt(6, s.getReminderOffsetDays());
            pstmt.setInt(7, s.getId());

            int rows = pstmt.executeUpdate();
            return rows > 0;
        } catch (SQLException e) {
            System.err.println("Error updating stage: " + e.getMessage());
        }
        return false;
    }

    // DELETE a stage
    public static boolean delete(int id) {
        String sql = "DELETE FROM stages WHERE id = ?";
        try (Connection conn = Database.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setInt(1, id);
            int rows = pstmt.executeUpdate();
            return rows > 0;
        } catch (SQLException e) {
            System.err.println("Error deleting stage: " + e.getMessage());
        }
        return false;
    }

    // DELETE all stages for a hackathon
    public static boolean deleteAllByHackathon(int hackathonId) {
        String sql = "DELETE FROM stages WHERE hackathonId = ?";
        try (Connection conn = Database.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setInt(1, hackathonId);
            int rows = pstmt.executeUpdate();
            return rows > 0;
        } catch (SQLException e) {
            System.err.println("Error deleting all stages: " + e.getMessage());
        }
        return false;
    }

    // Check if hackathon has any UPCOMING stages
    public static boolean hasUpcomingStages(int hackathonId) {
        String sql = "SELECT COUNT(*) FROM stages WHERE hackathonId = ? AND status = 'UPCOMING'";
        try (Connection conn = Database.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setInt(1, hackathonId);
            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt(1) > 0;
                }
            }
        } catch (SQLException e) {
            System.err.println("Error checking upcoming stages: " + e.getMessage());
        }
        return false;
    }

    // Check if hackathon has any SUBMITTED stages
    public static boolean hasSubmittedStages(int hackathonId) {
        String sql = "SELECT COUNT(*) FROM stages WHERE hackathonId = ? AND status = 'SUBMITTED'";
        try (Connection conn = Database.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setInt(1, hackathonId);
            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt(1) > 0;
                }
            }
        } catch (SQLException e) {
            System.err.println("Error checking submitted stages: " + e.getMessage());
        }
        return false;
    }

    // Check if any stage is ELIMINATED in this hackathon
    public static boolean hasEliminatedStage(int hackathonId) {
        String sql = "SELECT COUNT(*) FROM stages WHERE hackathonId = ? AND status = 'ELIMINATED'";
        try (Connection conn = Database.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setInt(1, hackathonId);
            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt(1) > 0;
                }
            }
        } catch (SQLException e) {
            System.err.println("Error checking eliminated stages: " + e.getMessage());
        }
        return false;
    }
}