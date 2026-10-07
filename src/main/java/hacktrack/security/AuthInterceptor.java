package hacktrack.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Session authentication for every route.
 *
 * - Public paths (login/signup, static assets, the Gmail OAuth callback) pass
 *   through without a session.
 * - API calls without a session get a 401 JSON body.
 * - Page loads without a session are redirected to /login.html.
 *
 * Authentication lives here so no endpoint can forget to check it.
 */
public class AuthInterceptor implements HandlerInterceptor {

    public static final String SESSION_USER_ID = "userId";

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        String path = request.getRequestURI().substring(request.getContextPath().length());

        if (isPublic(path)) {
            return true;
        }

        HttpSession session = request.getSession(false);
        Object userId = session != null ? session.getAttribute(SESSION_USER_ID) : null;
        if (userId == null) {
            if (isApi(path)) {
                response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                response.setContentType("application/json");
                response.setCharacterEncoding("UTF-8");
                response.getWriter().write("{\"error\":\"Authentication required.\",\"code\":\"UNAUTHENTICATED\"}");
            } else {
                response.sendRedirect(request.getContextPath() + "/login.html");
            }
            return false;
        }

        request.setAttribute(SESSION_USER_ID, userId);
        return true;
    }

    private static boolean isApi(String path) {
        return path.equals("/api") || path.startsWith("/api/");
    }

    private static boolean isPublic(String path) {
        return path.startsWith("/api/auth/")
                || path.equals("/api/gmail/callback")
                || path.equals("/login.html")
                || path.startsWith("/css/")
                || path.startsWith("/js/")
                || path.equals("/favicon.ico")
                || path.equals("/error");
    }
}
