package com.sithija.chat.auth;

import com.sithija.chat.ClientIpResolver;
import com.sithija.chat.ws.SessionAuthenticator;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Set;

/**
 * Authentication and CSRF protection for /api/**, in one short filter instead of Spring Security.
 *
 * Why hand-written: the whole policy is "state-changing requests need an allowed Origin; every
 * /api request except these three needs a valid session cookie", and it fits on one screen here.
 * Spring Security would bring a chain of ~15 filters, its own session and CSRF-token machinery
 * (which we'd have to switch off or adapt to our table), and defaults that must be known to be
 * reviewed. It also wouldn't cover the WebSocket port, which isn't a servlet, so the handshake
 * would check sessions one way and REST another. Here both call the same SessionService.
 * The cost is that we own this code, so it's kept small and fails closed.
 */
@Component
public class AuthFilter extends OncePerRequestFilter {

    /** Request attribute holding the authenticated username; controllers read it with @RequestAttribute. */
    public static final String USERNAME = "chat.auth.username";
    /** Request attribute holding the client's IP (a String), resolved through trusted proxies only. */
    public static final String CLIENT_IP = "chat.auth.clientIp";

    // Logout is public so a client with an already-expired cookie can still clear it.
    private static final Set<String> PUBLIC = Set.of("/api/auth/register", "/api/auth/login", "/api/auth/logout");
    private static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "OPTIONS");

    private final SessionService sessions;
    private final Set<String> allowedOrigins;
    private final ClientIpResolver clientIps;

    AuthFilter(SessionService sessions, AuthProperties props, ClientIpResolver clientIps) {
        this.sessions = sessions;
        this.allowedOrigins = Set.copyOf(props.allowedOrigins());
        this.clientIps = clientIps;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !path(request).startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        // First, so every /api request has it, including the ones rejected below. Resolved here
        // rather than by Tomcat's RemoteIpValve (server.forward-headers-strategy=native) so REST
        // and the raw WebSocket handshake share one implementation and one trust list.
        request.setAttribute(CLIENT_IP, clientIp(request).getHostAddress());

        // CSRF. SameSite=Lax already stops the cookie riding along on cross-site POSTs, but "site"
        // is looser than "origin": another port on localhost, or a sibling subdomain, counts as
        // same-site and would still get the cookie. Browsers always send Origin on POST/PUT/DELETE
        // and scripts can't forge it, so an exact allowlist closes that gap. A missing Origin is
        // rejected too (fail closed); only non-browser clients omit it, and they can set it.
        // Login and register are covered as well: a forged login could sign the victim into the
        // attacker's account, and whatever they then send would go to the attacker.
        String origin = request.getHeader("Origin");
        // Null-checked first: Set.copyOf's contains(null) throws rather than returning false.
        if (!SAFE_METHODS.contains(request.getMethod()) && (origin == null || !allowedOrigins.contains(origin))) {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            return;
        }
        if (PUBLIC.contains(path(request))) {
            chain.doFilter(request, response);
            return;
        }
        var session = sessions.find(cookieValue(request));
        if (session.isEmpty()) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            return;
        }
        request.setAttribute(USERNAME, session.get().username());
        chain.doFilter(request, response);
    }

    private InetAddress clientIp(HttpServletRequest request) throws UnknownHostException {
        // getRemoteAddr() is always an IP literal from the socket (no DNS lookup): Tomcat doesn't
        // resolve names unless enableLookups is on, which Spring Boot leaves off.
        InetAddress peer = InetAddress.getByName(request.getRemoteAddr());
        return clientIps.resolve(peer, request.getHeader("X-Forwarded-For"));
    }

    static String cookieValue(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies != null) {
            for (Cookie c : cookies) {
                if (SessionAuthenticator.COOKIE_NAME.equals(c.getName())) {
                    return c.getValue();
                }
            }
        }
        return null;
    }

    // The decoded, normalized path that Spring MVC routes on. Not getRequestURI(): that's the raw
    // URI, so "/%61pi/conversations" wouldn't start with "/api/" here yet would still reach the
    // controller. (Even then it fails closed: controllers require the USERNAME attribute.)
    private static String path(HttpServletRequest request) {
        String info = request.getPathInfo();
        return request.getServletPath() + (info == null ? "" : info);
    }
}
