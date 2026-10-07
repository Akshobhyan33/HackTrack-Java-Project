package hacktrack;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import hacktrack.controller.AuthController;
import hacktrack.controller.AiScheduleController;
import hacktrack.controller.HackathonController;
import hacktrack.dao.UserDAO;
import hacktrack.model.User;
import hacktrack.security.AuthInterceptor;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Session authentication + per-user data isolation tests.
 *
 * Runs against a throwaway SQLite file (never the real hacktrack.db) with the
 * same AuthInterceptor the application registers, so every request goes
 * through the real authentication and ownership checks.
 */
class AuthApiTest {

    static {
        try {
            Path dir = Files.createTempDirectory("hacktrack-authtest");
            System.setProperty("hacktrack.db.path", dir.resolve("auth-test.db").toString());
        } catch (IOException e) {
            throw new IllegalStateException("Could not create test database directory", e);
        }
    }

    private static final Gson GSON = new Gson();
    private static final AtomicLong COUNTER = new AtomicLong();
    private static MockMvc mockMvc;

    @BeforeAll
    static void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(
                new AuthController(),
                new HackathonController(),
                new AiScheduleController(null))
                .addInterceptors(new AuthInterceptor())
                .build();
    }

    // ── 1. Sign up ──

    @Test
    void signupStoresHashedPasswordAndCreatesSession() throws Exception {
        String email = uniqueEmail("signup");
        String password = "Secret123!";

        MvcResult result = mockMvc.perform(post("/api/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", "Signup User", "email", email, "password", password))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.email").value(email))
                .andExpect(jsonPath("$.name").value("Signup User"))
                .andExpect(jsonPath("$.passwordHash").doesNotExist())
                .andReturn();

        String body = result.getResponse().getContentAsString();
        assertFalse(body.contains(password), "password must not be echoed back");
        assertFalse(body.contains("pbkdf2"), "password hash must not be returned");

        MockHttpSession session = sessionOf(result);
        assertNotNull(session, "signup must create a server-side session");

        User stored = UserDAO.findByEmail(email);
        assertNotNull(stored);
        assertTrue(stored.getPasswordHash().startsWith("pbkdf2-sha256$"),
                "password must be stored hashed, not plaintext");
        assertFalse(stored.getPasswordHash().contains(password));

        mockMvc.perform(get("/api/auth/me").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(email));
    }

    // ── 2. Login ──

    @Test
    void loginCreatesSessionForValidCredentialsOnly() throws Exception {
        String email = uniqueEmail("login");
        String password = "Passw0rd!";
        signUp(email, password);

        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("email", email, "password", password))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(email))
                .andExpect(jsonPath("$.passwordHash").doesNotExist())
                .andReturn();

        MockHttpSession session = sessionOf(result);
        assertNotNull(session, "login must create a server-side session");

        mockMvc.perform(get("/api/auth/me").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(email));

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("email", email, "password", "wrong-password"))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
    }

    // ── 3. Logout ──

    @Test
    void logoutInvalidatesTheSession() throws Exception {
        String email = uniqueEmail("logout");
        MockHttpSession session = signUp(email, "Passw0rd!");

        mockMvc.perform(post("/api/auth/logout").session(session))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/auth/me").session(session))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/api/hackathons").session(session))
                .andExpect(status().isUnauthorized());
    }

    // ── 4. Duplicate email ──

    @Test
    void duplicateEmailIsRejected() throws Exception {
        String email = uniqueEmail("dup");
        signUp(email, "Passw0rd!");

        mockMvc.perform(post("/api/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", "Twin", "email", email, "password", "Other123!"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EMAIL_TAKEN"));

        // Different case, same mailbox: still a duplicate.
        mockMvc.perform(post("/api/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", "Twin", "email", email.toUpperCase(), "password", "Other123!"))))
                .andExpect(status().isConflict());
    }

    // ── 5. User A cannot see user B's hackathon ──

    @Test
    void userACannotSeeUsersBHackathon() throws Exception {
        MockHttpSession userA = signUpNewUser("alice");
        MockHttpSession userB = signUpNewUser("bob");

        int bHackathonId = createHackathon(userB, "SIH Hackathon");

        mockMvc.perform(get("/api/hackathons/" + bHackathonId).session(userA))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/hackathons/" + bHackathonId + "/stages").session(userA))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/hackathons/" + bHackathonId + "/overall-status").session(userA))
                .andExpect(status().isForbidden());

        // B still sees their own hackathon.
        mockMvc.perform(get("/api/hackathons/" + bHackathonId).session(userB))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("SIH Hackathon"));

        // The list endpoints never leak across accounts.
        JsonArray aList = listHackathons(userA);
        assertTrue(aList.isEmpty(), "A must not see B's hackathon in the list");
        JsonArray bList = listHackathons(userB);
        assertEquals(1, bList.size());
        assertEquals(bHackathonId, bList.get(0).getAsJsonObject().get("id").getAsInt());
    }

    // ── 6. User A cannot edit/delete user B's hackathon ──

    @Test
    void userACannotEditOrDeleteUsersBHackathon() throws Exception {
        MockHttpSession userA = signUpNewUser("carol");
        MockHttpSession userB = signUpNewUser("dave");

        int bHackathonId = createHackathon(userB, "HackTrack Cup");
        int bStageId = createStage(userB, bHackathonId, "Round 1");

        mockMvc.perform(put("/api/hackathons/" + bHackathonId)
                        .session(userA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", "hacked"))))
                .andExpect(status().isForbidden());

        mockMvc.perform(put("/api/hackathons/" + bHackathonId + "/toggle-star").session(userA))
                .andExpect(status().isForbidden());

        mockMvc.perform(delete("/api/hackathons/" + bHackathonId).session(userA))
                .andExpect(status().isForbidden());

        mockMvc.perform(put("/api/stages/" + bStageId)
                        .session(userA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", "hacked"))))
                .andExpect(status().isForbidden());

        mockMvc.perform(delete("/api/stages/" + bStageId).session(userA))
                .andExpect(status().isForbidden());

        // AI schedule endpoints refuse foreign hackathons too.
        mockMvc.perform(post("/api/hackathons/" + bHackathonId + "/ai-schedule")
                        .session(userA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden());

        // B's data is untouched.
        mockMvc.perform(get("/api/hackathons/" + bHackathonId).session(userB))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("HackTrack Cup"));
        mockMvc.perform(get("/api/hackathons/" + bHackathonId + "/stages").session(userB))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
    }

    // ── 7. Unauthenticated API access is rejected ──

    @Test
    void unauthenticatedApiAccessIsRejected() throws Exception {
        mockMvc.perform(get("/api/hackathons")).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        mockMvc.perform(post("/api/hackathons")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", "sneaky"))))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/history")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/gmail/status")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/gmail/recipient")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("email", "x@y.z"))))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/hackathons/1/ai-schedule")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/hackathons/1/ai-schedule/apply")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/auth/me")).andExpect(status().isUnauthorized());
    }

    // ── History and email settings are per user ──

    @Test
    void historyIsIsolatedPerUser() throws Exception {
        MockHttpSession userA = signUpNewUser("erin");
        MockHttpSession userB = signUpNewUser("frank");

        int aHackathonId = createHackathon(userA, "Histo Hackathon");
        MvcResult historyResult = mockMvc.perform(post("/api/history")
                        .session(userA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("hackathonId", aHackathonId, "finalOutcome", "Completed — Histo"))))
                .andExpect(status().isOk())
                .andReturn();
        JsonObject historyBody = GSON.fromJson(historyResult.getResponse().getContentAsString(), JsonObject.class);
        int historyId = historyBody.get("id").getAsInt();

        mockMvc.perform(get("/api/history").session(userA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));

        mockMvc.perform(get("/api/history").session(userB))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));

        mockMvc.perform(delete("/api/history/" + historyId).session(userB))
                .andExpect(status().isForbidden());

        // B did not manage to delete A's history row.
        mockMvc.perform(get("/api/history").session(userA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void recipientEmailSettingsArePerUser() throws Exception {
        MockHttpSession userA = signUpNewUser("grace");
        MockHttpSession userB = signUpNewUser("heidi");

        mockMvc.perform(post("/api/gmail/recipient")
                        .session(userA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("email", "grace@example.com"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recipientEmail").value("grace@example.com"));

        JsonObject aStatus = gmailStatus(userA);
        assertEquals("grace@example.com", aStatus.get("recipientEmail").getAsString());

        JsonObject bStatus = gmailStatus(userB);
        assertTrue(!bStatus.has("recipientEmail") || bStatus.get("recipientEmail").isJsonNull(),
                "one user's recipient email must not leak to another user");
    }

    // ── Helpers ──

    private static String json(Map<String, Object> map) {
        return GSON.toJson(map);
    }

    private static String uniqueEmail(String prefix) {
        return prefix + "-" + COUNTER.incrementAndGet() + "@test.local";
    }

    private static MockHttpSession sessionOf(MvcResult result) {
        return (MockHttpSession) result.getRequest().getSession(false);
    }

    private static MockHttpSession signUp(String email, String password) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", "Test User", "email", email, "password", password))))
                .andExpect(status().isCreated())
                .andReturn();
        MockHttpSession session = sessionOf(result);
        assertNotNull(session);
        return session;
    }

    private static MockHttpSession signUpNewUser(String name) throws Exception {
        return signUp(uniqueEmail(name), "Passw0rd!");
    }

    private static int createHackathon(MockHttpSession session, String name) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/hackathons")
                        .session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", name, "websiteUrl", ""))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").isNumber())
                .andReturn();
        JsonObject body = GSON.fromJson(result.getResponse().getContentAsString(), JsonObject.class);
        return body.get("id").getAsInt();
    }

    private static int createStage(MockHttpSession session, int hackathonId, String name) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/hackathons/" + hackathonId + "/stages")
                        .session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", name,
                                "deadline", LocalDate.now().plusDays(10).toString()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").isNumber())
                .andReturn();
        JsonObject body = GSON.fromJson(result.getResponse().getContentAsString(), JsonObject.class);
        return body.get("id").getAsInt();
    }

    private static JsonArray listHackathons(MockHttpSession session) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/hackathons").session(session))
                .andExpect(status().isOk())
                .andReturn();
        return GSON.fromJson(result.getResponse().getContentAsString(), JsonArray.class);
    }

    private static JsonObject gmailStatus(MockHttpSession session) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/gmail/status").session(session))
                .andExpect(status().isOk())
                .andReturn();
        return GSON.fromJson(result.getResponse().getContentAsString(), JsonObject.class);
    }
}
