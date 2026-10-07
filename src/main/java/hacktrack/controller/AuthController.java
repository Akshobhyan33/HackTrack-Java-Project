package hacktrack.controller;

import hacktrack.dao.UserDAO;
import hacktrack.model.User;
import hacktrack.security.AuthInterceptor;
import hacktrack.security.PasswordHasher;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

/**
 * Session based sign up / login / logout.
 *
 * - Passwords are stored only as salted PBKDF2 hashes.
 * - The response maps contain id/name/email only: never the hash.
 * - No password is ever logged.
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private static final int MIN_PASSWORD_LENGTH = 6;
    private static final String EMAIL_PATTERN = "^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$";

    @PostMapping("/signup")
    public ResponseEntity<Map<String, Object>> signup(@RequestBody Map<String, Object> body,
                                                      HttpServletRequest request) {
        String name = asString(body.get("name"));
        String email = asString(body.get("email"));
        String password = asString(body.get("password"));

        if (isBlank(name) || isBlank(email) || password == null || password.length() < MIN_PASSWORD_LENGTH) {
            return badRequest("Name, email and a password of at least "
                    + MIN_PASSWORD_LENGTH + " characters are required.", "INVALID_INPUT");
        }

        String normalizedEmail = email.trim().toLowerCase();
        if (!normalizedEmail.matches(EMAIL_PATTERN)) {
            return badRequest("Please enter a valid email address.", "INVALID_EMAIL");
        }

        if (UserDAO.findByEmail(normalizedEmail) != null) {
            return emailTaken();
        }

        User user = new User(0, name.trim(), normalizedEmail, PasswordHasher.hash(password));
        int userId = UserDAO.insert(user);
        if (userId <= 0) {
            // Lost a race against another signup with the same email.
            if (UserDAO.findByEmail(normalizedEmail) != null) {
                return emailTaken();
            }
            return ResponseEntity.internalServerError().body(errorBody("SIGNUP_FAILED",
                    "Could not create the account. Please try again."));
        }

        startSession(request, user);
        return ResponseEntity.status(201).body(userSummary(user));
    }

    @PostMapping("/login")
    public ResponseEntity<Map<String, Object>> login(@RequestBody Map<String, Object> body,
                                                     HttpServletRequest request) {
        String email = asString(body.get("email"));
        String password = asString(body.get("password"));

        if (isBlank(email) || password == null || password.isBlank()) {
            return badRequest("Email and password are required.", "INVALID_INPUT");
        }

        User user = UserDAO.findByEmail(email.trim().toLowerCase());
        if (user == null || !PasswordHasher.verify(password, user.getPasswordHash())) {
            return ResponseEntity.status(401).body(errorBody("INVALID_CREDENTIALS",
                    "Invalid email or password."));
        }

        startSession(request, user);
        return ResponseEntity.ok(userSummary(user));
    }

    @PostMapping("/logout")
    public ResponseEntity<Map<String, Object>> logout(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        Map<String, Object> map = new HashMap<>();
        map.put("success", true);
        return ResponseEntity.ok(map);
    }

    @GetMapping("/me")
    public ResponseEntity<Map<String, Object>> me(HttpServletRequest request) {
        Integer userId = currentUserId(request);
        if (userId == null) {
            return ResponseEntity.status(401).body(errorBody("UNAUTHENTICATED", "Not signed in."));
        }
        User user = UserDAO.getById(userId);
        if (user == null) {
            HttpSession session = request.getSession(false);
            if (session != null) session.invalidate();
            return ResponseEntity.status(401).body(errorBody("UNAUTHENTICATED", "Not signed in."));
        }
        return ResponseEntity.ok(userSummary(user));
    }

    // ── Helpers ──

    /** Session attribute set by the login/signup flow (and by AuthInterceptor). */
    public static Integer currentUserId(HttpServletRequest request) {
        Object value = request.getAttribute(AuthInterceptor.SESSION_USER_ID);
        if (value == null) {
            HttpSession session = request.getSession(false);
            value = session != null ? session.getAttribute(AuthInterceptor.SESSION_USER_ID) : null;
        }
        return value instanceof Integer id ? id : null;
    }

    private static void startSession(HttpServletRequest request, User user) {
        HttpSession old = request.getSession(false);
        if (old != null) {
            old.invalidate();
        }
        HttpSession session = request.getSession(true);
        session.setAttribute(AuthInterceptor.SESSION_USER_ID, user.getId());
    }

    private static Map<String, Object> userSummary(User user) {
        Map<String, Object> map = new HashMap<>();
        map.put("id", user.getId());
        map.put("name", user.getName());
        map.put("email", user.getEmail());
        return map;
    }

    private static ResponseEntity<Map<String, Object>> emailTaken() {
        return ResponseEntity.status(409).body(errorBody("EMAIL_TAKEN",
                "An account with that email already exists."));
    }

    private static ResponseEntity<Map<String, Object>> badRequest(String message, String code) {
        return ResponseEntity.badRequest().body(errorBody(code, message));
    }

    private static Map<String, Object> errorBody(String code, String message) {
        Map<String, Object> map = new HashMap<>();
        map.put("error", message);
        map.put("code", code);
        return map;
    }

    private static String asString(Object value) {
        return value instanceof String s ? s : null;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
