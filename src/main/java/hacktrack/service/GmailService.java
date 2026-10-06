package hacktrack.service;

import com.google.api.client.auth.oauth2.Credential;
import com.google.api.client.auth.oauth2.BearerToken;
import com.google.api.client.auth.oauth2.ClientParametersAuthentication;
import com.google.api.client.googleapis.auth.oauth2.GoogleAuthorizationCodeFlow;
import com.google.api.client.googleapis.auth.oauth2.GoogleClientSecrets;
import com.google.api.client.googleapis.auth.oauth2.GoogleTokenResponse;
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.http.GenericUrl;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.JsonFactory;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.services.gmail.Gmail;
import com.google.api.services.gmail.GmailScopes;
import com.google.api.services.gmail.model.Message;

import javax.mail.Session;
import javax.mail.internet.InternetAddress;
import javax.mail.internet.MimeBodyPart;
import javax.mail.internet.MimeMessage;
import javax.mail.internet.MimeMultipart;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Properties;
import java.util.Set;

public class GmailService {

    private static final String APP_NAME = "HackTrack";
    private static final JsonFactory JSON_FACTORY = GsonFactory.getDefaultInstance();
    private static final String CREDENTIALS_PATH = "config/google-credentials.json";
    private static final String TOKENS_DIR = "config";
    private static final String TOKENS_FILE = "gmail-tokens.json";
    private static final String REDIRECT_URI = "http://localhost:8080/api/gmail/callback";
    private static final Set<String> SCOPES = Set.of(
            GmailScopes.GMAIL_SEND,
            GmailScopes.GMAIL_METADATA
    );
    private static final DateTimeFormatter DEADLINE_FORMAT = DateTimeFormatter.ofPattern("MMMM d, yyyy");

    private static Gmail gmailService;
    private static String accessToken;
    private static String refreshToken;
    private static long tokenExpiry;
    private static String userEmail;
    private static String lastReminderInfo;
    private static final Path TOKEN_PATH = Paths.get(TOKENS_DIR, TOKENS_FILE);

    public static String getAuthorizationUrl() {
        try {
            GoogleClientSecrets clientSecrets = loadClientSecrets();
            NetHttpTransport httpTransport = GoogleNetHttpTransport.newTrustedTransport();
            GoogleAuthorizationCodeFlow flow = new GoogleAuthorizationCodeFlow.Builder(
                    httpTransport, JSON_FACTORY, clientSecrets, SCOPES)
                    .setAccessType("offline")
                    .build();
            return flow.newAuthorizationUrl().setRedirectUri(REDIRECT_URI).build();
        } catch (Exception e) {
            System.err.println("Error generating auth URL: " + e.getMessage());
            return null;
        }
    }

    public static boolean handleCallback(String authorizationCode) {
        try {
            GoogleClientSecrets clientSecrets = loadClientSecrets();
            NetHttpTransport httpTransport = GoogleNetHttpTransport.newTrustedTransport();
            GoogleAuthorizationCodeFlow flow = new GoogleAuthorizationCodeFlow.Builder(
                    httpTransport, JSON_FACTORY, clientSecrets, SCOPES)
                    .setAccessType("offline")
                    .build();

            GoogleTokenResponse tokenResponse = flow.newTokenRequest(authorizationCode)
                    .setRedirectUri(REDIRECT_URI)
                    .execute();

            accessToken = tokenResponse.getAccessToken();
            refreshToken = tokenResponse.getRefreshToken();
            tokenExpiry = System.currentTimeMillis() + (tokenResponse.getExpiresInSeconds() * 1000);

            saveTokensToFile();

            Credential credential = buildCredential(httpTransport, clientSecrets, accessToken, refreshToken);
            gmailService = new Gmail.Builder(httpTransport, JSON_FACTORY, credential)
                    .setApplicationName(APP_NAME)
                    .build();

            userEmail = fetchUserEmail(gmailService);
            System.out.println("Gmail connected successfully for: " + userEmail);
            return true;
        } catch (Exception e) {
            System.err.println("OAuth callback error: " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }

    public static boolean isConnected() {
        return gmailService != null && accessToken != null;
    }

    public static String getUserEmail() {
        return userEmail;
    }

    public static String getLastReminderInfo() {
        return lastReminderInfo;
    }

    public static void setLastReminderInfo(String info) {
        lastReminderInfo = info;
    }

    public static boolean disconnect() {
        try {
            gmailService = null;
            accessToken = null;
            refreshToken = null;
            tokenExpiry = 0;
            userEmail = null;
            lastReminderInfo = null;
            if (Files.exists(TOKEN_PATH)) {
                Files.delete(TOKEN_PATH);
            }
            return true;
        } catch (Exception e) {
            System.err.println("Error disconnecting Gmail: " + e.getMessage());
            return false;
        }
    }

    public static void init() {
        if (loadTokensFromFile()) {
            restoreServiceFromTokens();
        }
    }

    public static boolean sendReminderEmail(String recipientEmail, String hackathonName,
            String hackathonUrl, String stageName, LocalDate deadline, int daysRemaining) {
        if (!isConnected()) {
            System.err.println("Gmail not connected. Cannot send reminder.");
            return false;
        }
        try {
            String subject = "HackTrack Reminder: " + stageName + " for " + hackathonName;
            String[] bodyParts = composeEmailBody(hackathonName, hackathonUrl, stageName, deadline, daysRemaining);
            MimeMessage mimeMessage = createEmailMessage(recipientEmail, subject, bodyParts[0], bodyParts[1]);
            Message message = createGmailMessage(mimeMessage);

            gmailService.users().messages().send("me", message).execute();

            lastReminderInfo = "Sent: " + stageName + " for " + hackathonName
                    + " (" + daysRemaining + " day" + (daysRemaining != 1 ? "s" : "") + " remaining)";
            System.out.println("Reminder email sent to " + recipientEmail + ": " + subject);
            return true;
        } catch (Exception e) {
            System.err.println("Failed to send reminder email: " + e.getMessage());
            e.printStackTrace();
            // Extract Google API error details if available
            String apiError = extractGoogleApiError(e);
            System.err.println("Google API error: " + apiError);
            // Store the error details so they can be retrieved by the controller
            lastReminderInfo = "error:" + apiError;
            return false;
        }
    }

    /**
     * Extracts the complete Google API error including HTTP status, error reason, and error message.
     * Handles GoogleJsonResponseException and other exceptions.
     * Uses only APIs that exist in google-api-client 2.2.0 / google-http-client 1.43.3:
     * GoogleJsonError#getCode(), #getMessage(), #getErrors() -> ErrorInfo#getReason().
     */
    private static String extractGoogleApiError(Exception e) {
        if (e instanceof com.google.api.client.googleapis.json.GoogleJsonResponseException) {
            com.google.api.client.googleapis.json.GoogleJsonResponseException ex =
                    (com.google.api.client.googleapis.json.GoogleJsonResponseException) e;
            int statusCode = ex.getStatusCode();
            var details = ex.getDetails();
            if (details != null) {
                int code = details.getCode();
                String msg = details.getMessage();
                String reason = null;
                if (details.getErrors() != null && !details.getErrors().isEmpty()
                        && details.getErrors().get(0) != null) {
                    reason = details.getErrors().get(0).getReason();
                }
                StringBuilder sb = new StringBuilder("HTTP ").append(statusCode);
                if (code != 0) sb.append(" (code ").append(code).append(")");
                if (reason != null && !reason.isEmpty()) sb.append(" - ").append(reason);
                if (msg != null && !msg.isEmpty()) sb.append(": ").append(msg);
                // Fallback to raw details string if nothing else
                String built = sb.toString();
                if (!built.equals("HTTP " + statusCode) && !built.equals("HTTP " + statusCode + " (code " + code + ")")) {
                    return built;
                }
                return built + (msg != null ? "" : " " + details);
            }
            return "HTTP " + statusCode + ": " + ex.getMessage();
        }
        return e.getMessage();
    }

    // ── Private Helpers ──

    private static GoogleClientSecrets loadClientSecrets() throws IOException {
        try (Reader reader = new FileReader(CREDENTIALS_PATH)) {
            return GoogleClientSecrets.load(JSON_FACTORY, reader);
        }
    }

    private static Credential buildCredential(NetHttpTransport httpTransport,
            GoogleClientSecrets clientSecrets, String at, String rt) {
        Credential credential = new Credential.Builder(BearerToken.authorizationHeaderAccessMethod())
                .setTransport(httpTransport)
                .setJsonFactory(JSON_FACTORY)
                .setTokenServerUrl(new GenericUrl("https://oauth2.googleapis.com/token"))
                .setClientAuthentication(new ClientParametersAuthentication(
                        clientSecrets.getWeb().getClientId(),
                        clientSecrets.getWeb().getClientSecret()))
                .build();
        credential.setAccessToken(at);
        credential.setRefreshToken(rt);
        return credential;
    }

    private static String fetchUserEmail(Gmail service) {
        try {
            com.google.api.services.gmail.model.Profile profile = service.users().getProfile("me").execute();
            return profile.getEmailAddress();
        } catch (Exception e) {
            System.err.println("Could not fetch Gmail address: " + e.getMessage());
            return null;
        }
    }

    private static MimeMessage createEmailMessage(String to, String subject,
            String textBody, String htmlBody) throws Exception {
        Properties props = new Properties();
        Session session = Session.getInstance(props);
        MimeMessage mimeMessage = new MimeMessage(session);
        mimeMessage.setFrom(new InternetAddress(userEmail));
        mimeMessage.addRecipient(javax.mail.Message.RecipientType.TO, new InternetAddress(to));
        mimeMessage.setSubject(subject, "UTF-8");

        MimeMultipart multipart = new MimeMultipart("alternative");

        MimeBodyPart textPart = new MimeBodyPart();
        textPart.setText(textBody, "UTF-8");
        multipart.addBodyPart(textPart);

        MimeBodyPart htmlPart = new MimeBodyPart();
        htmlPart.setContent(htmlBody, "text/html; charset=UTF-8");
        multipart.addBodyPart(htmlPart);

        mimeMessage.setContent(multipart);
        mimeMessage.saveChanges();
        return mimeMessage;
    }

    private static Message createGmailMessage(MimeMessage mimeMessage) throws Exception {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        mimeMessage.writeTo(buffer);
        byte[] bytes = buffer.toByteArray();
        String encoded = com.google.api.client.util.Base64.encodeBase64URLSafeString(bytes);
        Message message = new Message();
        message.setRaw(encoded);
        return message;
    }

    private static String[] composeEmailBody(String hackathonName, String hackathonUrl,
            String stageName, LocalDate deadline, int daysRemaining) {
        String deadlineStr = deadline != null ? deadline.format(DEADLINE_FORMAT) : "N/A";

        String daysText;
        if (daysRemaining < 0) {
            daysText = "This deadline has passed!";
        } else if (daysRemaining == 0) {
            daysText = "This is due today!";
        } else if (daysRemaining == 1) {
            daysText = "1 day remaining.";
        } else {
            daysText = daysRemaining + " days remaining.";
        }

        String textBody = "HackTrack Reminder\n\n"
                + "Hackathon: " + hackathonName + "\n"
                + "Stage: " + stageName + "\n"
                + "Deadline: " + deadlineStr + "\n"
                + daysText + "\n";
        if (hackathonUrl != null && !hackathonUrl.isEmpty()) {
            textBody += "URL: " + hackathonUrl + "\n";
        }
        textBody += "\n— HackTrack";

        String htmlBody = "<!DOCTYPE html><html><head><style>"
                + "body{font-family:Arial,sans-serif;color:#333;line-height:1.6}"
                + ".container{max-width:600px;margin:0 auto;padding:20px}"
                + ".header{background:#4f46e5;color:#fff;padding:16px 24px;border-radius:8px 8px 0 0}"
                + ".content{background:#fff;padding:24px;border:1px solid #e5e7eb;border-top:none;border-radius:0 0 8px 8px}"
                + ".label{font-weight:600;color:#374151}"
                + ".value{color:#1f2937;margin-bottom:12px}"
                + ".deadline{font-size:1.1em;color:#dc2626;font-weight:600}"
                + ".days{font-size:1.05em;color:#4f46e5;font-weight:600;margin:16px 0}"
                + ".url a{color:#4f46e5}"
                + ".footer{margin-top:16px;font-size:0.85em;color:#9ca3af}"
                + "</style></head><body>"
                + "<div class='container'>"
                + "<div class='header'><h2 style='margin:0;color:#fff'>&#9889; HackTrack Reminder</h2></div>"
                + "<div class='content'>"
                + "<p class='value'><span class='label'>Hackathon:</span> " + escapeHtml(hackathonName) + "</p>"
                + "<p class='value'><span class='label'>Stage:</span> " + escapeHtml(stageName) + "</p>"
                + "<p class='deadline'>Deadline: " + escapeHtml(deadlineStr) + "</p>"
                + "<p class='days'>" + escapeHtml(daysText) + "</p>";

        if (hackathonUrl != null && !hackathonUrl.isEmpty()) {
            htmlBody += "<p class='url'><span class='label'>URL:</span> <a href='" + escapeHtml(hackathonUrl) + "'>"
                    + escapeHtml(hackathonUrl) + "</a></p>";
        }

        htmlBody += "<div class='footer'>This reminder was sent by HackTrack.</div>"
                + "</div></div></body></html>";

        return new String[]{textBody, htmlBody};
    }

    private static String escapeHtml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }

    private static void saveTokensToFile() {
        try {
            Files.createDirectories(TOKEN_PATH.getParent());
            String json = "{\"accessToken\":\"" + escapeJson(accessToken)
                    + "\",\"refreshToken\":\"" + escapeJson(refreshToken)
                    + "\",\"expiry\":" + tokenExpiry + "}";
            Files.writeString(TOKEN_PATH, json);
        } catch (Exception e) {
            System.err.println("Error saving tokens: " + e.getMessage());
        }
    }

    private static boolean loadTokensFromFile() {
        try {
            if (!Files.exists(TOKEN_PATH)) return false;
            String json = Files.readString(TOKEN_PATH);
            String at = extractJsonString(json, "accessToken");
            String rt = extractJsonString(json, "refreshToken");
            long exp = extractJsonLong(json, "expiry");
            if (at == null || at.isEmpty()) return false;
            accessToken = at;
            refreshToken = rt;
            tokenExpiry = exp;
            return true;
        } catch (Exception e) {
            System.err.println("Error loading tokens: " + e.getMessage());
            return false;
        }
    }

    private static void restoreServiceFromTokens() {
        try {
            if (accessToken == null || refreshToken == null) return;
            GoogleClientSecrets clientSecrets = loadClientSecrets();
            NetHttpTransport httpTransport = GoogleNetHttpTransport.newTrustedTransport();

            if (System.currentTimeMillis() >= tokenExpiry) {
                GoogleTokenResponse refreshResponse = new com.google.api.client.googleapis.auth.oauth2
                        .GoogleRefreshTokenRequest(httpTransport, JSON_FACTORY,
                                refreshToken, clientSecrets.getWeb().getClientId(),
                                clientSecrets.getWeb().getClientSecret())
                        .execute();
                accessToken = refreshResponse.getAccessToken();
                if (refreshResponse.getRefreshToken() != null) {
                    refreshToken = refreshResponse.getRefreshToken();
                }
                tokenExpiry = System.currentTimeMillis() + (refreshResponse.getExpiresInSeconds() * 1000);
                saveTokensToFile();
            }

            Credential credential = buildCredential(httpTransport, clientSecrets, accessToken, refreshToken);
            gmailService = new Gmail.Builder(httpTransport, JSON_FACTORY, credential)
                    .setApplicationName(APP_NAME)
                    .build();
            userEmail = fetchUserEmail(gmailService);
            System.out.println("Gmail restored from stored tokens for: " + userEmail);
        } catch (Exception e) {
            System.err.println("Could not restore Gmail service: " + e.getMessage());
        }
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String extractJsonString(String json, String key) {
        String search = "\"" + key + "\":\"";
        int start = json.indexOf(search);
        if (start < 0) return null;
        start += search.length();
        int end = json.indexOf("\"", start);
        if (end < 0) return null;
        return json.substring(start, end);
    }

    private static long extractJsonLong(String json, String key) {
        String search = "\"" + key + "\":";
        int start = json.indexOf(search);
        if (start < 0) return 0;
        start += search.length();
        int end = json.indexOf(",", start);
        if (end < 0) end = json.indexOf("}", start);
        if (end < 0) return 0;
        try {
            return Long.parseLong(json.substring(start, end).trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
