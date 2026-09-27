package com.sithija.chat.auth;

import com.sithija.chat.auth.SessionService.NewSession;
import com.sithija.chat.ws.SessionAuthenticator;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService auth;
    private final AuthProperties props;

    AuthController(AuthService auth, AuthProperties props) {
        this.auth = auth;
        this.props = props;
    }

    record Credentials(String username, String password) { }

    record Me(String username) { }

    @PostMapping("/register")
    ResponseEntity<Me> register(@RequestBody Credentials c) {
        NewSession s = auth.register(c.username(), c.password());
        return withSession(ResponseEntity.status(HttpStatus.CREATED), s);
    }

    @PostMapping("/login")
    ResponseEntity<Me> login(@RequestBody Credentials c, HttpServletRequest request) {
        // The direct peer's address. Behind a reverse proxy this is the proxy for every client,
        // so production would read X-Forwarded-For as set by that trusted proxy.
        NewSession s = auth.login(c.username(), c.password(), request.getRemoteAddr());
        return withSession(ResponseEntity.ok(), s);
    }

    @PostMapping("/logout")
    ResponseEntity<Void> logout(HttpServletRequest request) {
        auth.logout(AuthFilter.cookieValue(request));
        return ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE, cookie("", Duration.ZERO)).build();
    }

    @GetMapping("/me")
    Me me(@RequestAttribute(AuthFilter.USERNAME) String username) {
        return new Me(username);
    }

    record ErrorBody(String error) { }

    // Auth errors carry a message the UI can show ("Username is taken"). Scoped to this controller,
    // rather than server.error.include-message, which would also expose internal exception text.
    // Every reason here is written by us and contains no user input.
    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<ErrorBody> error(ResponseStatusException e) {
        return ResponseEntity.status(e.getStatusCode()).body(new ErrorBody(e.getReason()));
    }

    // Handled here so Spring's default resolver never logs it: its WARN line includes Jackson's
    // message, which quotes the offending input. A client that double-encodes its JSON would put
    // the whole body, password included, into the server log.
    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ErrorBody> unreadable() {
        return ResponseEntity.badRequest().body(new ErrorBody("Malformed request body"));
    }

    private ResponseEntity<Me> withSession(ResponseEntity.BodyBuilder response, NewSession s) {
        return response.header(HttpHeaders.SET_COOKIE, cookie(s.rawToken(), props.sessionTtl()))
                .body(new Me(s.row().username()));
    }

    // HttpOnly: page scripts (and so any XSS) can't read the token. SameSite=Lax: not sent on
    // cross-site POSTs or subresource requests, only on top-level GET navigations. Path=/ so it
    // reaches every /api path. Max-Age matches the server-side expiry, so the browser drops it
    // when the server would reject it anyway. Cookies are scoped by host, not port, which is why
    // this cookie from :8080 is also sent on the WebSocket upgrade to :8081.
    private String cookie(String value, Duration maxAge) {
        return ResponseCookie.from(SessionAuthenticator.COOKIE_NAME, value)
                .httpOnly(true)
                .secure(props.cookieSecure())
                .sameSite("Lax")
                .path("/")
                .maxAge(maxAge)
                .build().toString();
    }
}
