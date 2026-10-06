package hacktrack.dao;

import hacktrack.model.Hackathon;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * Data Access Object for Hackathon entities.
 * Provides CRUD operations for the hackathons table.
 */
public class HackathonDAO {

    // INSERT a new hackathon
    public static int insert(Hackathon h) {
        String sql = "INSERT INTO hackathons (name, websiteUrl, isStarred, createdAt) VALUES (?, ?, ?, ?)";
        try (Connection conn = Database.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql, PreparedStatement.RETURN_GENERATED_KEYS)) {

            pstmt.setString(1, h.getName());
            pstmt.setString(2, h.getWebsiteUrl());
            pstmt.setInt(3, h.isStarred() ? 1 : 0);
            pstmt.setString(4, h.getCreatedAt().toString());

            pstmt.executeUpdate();

            // Return the generated key (new hackathon id)
            try (ResultSet rs = pstmt.getGeneratedKeys()) {
                if (rs.next()) {
                    h.setId(rs.getInt(1));
                    return rs.getInt(1);
                }
            }
        } catch (SQLException e) {
            System.err.println("Error inserting hackathon: " + e.getMessage());
        }
        return -1;
    }

    // GET a hackathon by ID
    public static Hackathon getById(int id) {
        String sql = "SELECT * FROM hackathons WHERE id = ?";
        try (Connection conn = Database.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setInt(1, id);
            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    Hackathon h = new Hackathon();
                    h.setId(rs.getInt("id"));
                    h.setName(rs.getString("name"));
                    h.setWebsiteUrl(rs.getString("websiteUrl"));
                    h.setStarred(rs.getInt("isStarred") == 1);
                    h.setCreatedAt(parseDateTime(rs.getString("createdAt")));
                    return h;
                }
            }
        } catch (SQLException e) {
            System.err.println("Error getting hackathon: " + e.getMessage());
        }
        return null;
    }

    // GET all hackathons, ordered by starred first then name
    public static java.util.List<Hackathon> getAll() {
        java.util.List<Hackathon> list = new java.util.ArrayList<>();
        String sql = "SELECT * FROM hackathons ORDER BY isStarred DESC, name ASC";
        try (Connection conn = Database.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {

            while (rs.next()) {
                Hackathon h = new Hackathon();
                h.setId(rs.getInt("id"));
                h.setName(rs.getString("name"));
                h.setWebsiteUrl(rs.getString("websiteUrl"));
                h.setStarred(rs.getInt("isStarred") == 1);
                h.setCreatedAt(parseDateTime(rs.getString("createdAt")));
                list.add(h);
            }
        } catch (SQLException e) {
            System.err.println("Error getting all hackathons: " + e.getMessage());
        }
        return list;
    }

    // UPDATE an existing hackathon
    public static boolean update(Hackathon h) {
        String sql = "UPDATE hackathons SET name = ?, websiteUrl = ?, isStarred = ? WHERE id = ?";
        try (Connection conn = Database.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setString(1, h.getName());
            pstmt.setString(2, h.getWebsiteUrl());
            pstmt.setInt(3, h.isStarred() ? 1 : 0);
            pstmt.setInt(4, h.getId());

            int rows = pstmt.executeUpdate();
            return rows > 0;
        } catch (SQLException e) {
            System.err.println("Error updating hackathon: " + e.getMessage());
        }
        return false;
    }

    // DELETE a hackathon (clean up dependent records first, then delete)
    public static boolean delete(int id) {
        // Delete dependent records first to avoid FK constraint issues
        // on existing databases where ON DELETE CASCADE may not be set
        try (Connection conn = Database.getConnection()) {
            conn.setAutoCommit(false);
            try (PreparedStatement ps1 = conn.prepareStatement("DELETE FROM reminder_history WHERE hackathonId = ?")) {
                ps1.setInt(1, id);
                ps1.executeUpdate();
            }
            try (PreparedStatement ps2 = conn.prepareStatement("DELETE FROM participation_history WHERE hackathonId = ?")) {
                ps2.setInt(1, id);
                ps2.executeUpdate();
            }
            try (PreparedStatement ps3 = conn.prepareStatement("DELETE FROM stages WHERE hackathonId = ?")) {
                ps3.setInt(1, id);
                ps3.executeUpdate();
            }
            try (PreparedStatement ps4 = conn.prepareStatement("DELETE FROM hackathons WHERE id = ?")) {
                ps4.setInt(1, id);
                int rows = ps4.executeUpdate();
                conn.commit();
                return rows > 0;
            }
        } catch (SQLException e) {
            System.err.println("Error deleting hackathon: " + e.getMessage());
        }
        return false;
    }

    private static LocalDateTime parseDateTime(String s) {
        if (s == null || s.isBlank()) return LocalDateTime.now();
        return LocalDateTime.parse(s.replace(' ', 'T'));
    }

    // Toggle star status
    public static boolean toggleStar(int id) {
        // First get current status
        Hackathon h = getById(id);
        if (h == null) return false;
        h.setStarred(!h.isStarred());
        return update(h);
    }
}