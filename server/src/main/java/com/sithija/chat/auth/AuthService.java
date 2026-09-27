package com.sithija.chat.auth;

import com.sithija.chat.Usernames;
import com.sithija.chat.auth.AuthRepository.Account;
import com.sithija.chat.auth.SessionService.NewSession;
import com.sithija.chat.ws.WsServer;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

@Service
public class AuthService {

    private static final int MIN_PASSWORD_CHARS = 8;
    // bcrypt only ever reads the first 72 bytes. Spring's encoder refuses to encode more, but its
    // matches() silently compares just the first 72 (verified on 7.1.1): a stored 72-byte password
    // would then also accept itself plus any suffix. Rejecting longer passwords on both paths keeps
    // "what you typed is what's checked" exactly true. Bytes, not chars: 'é' is 2 bytes in UTF-8.
    private static final int MAX_PASSWORD_BYTES = 72;

    private final AuthRepository repo;
    private final SessionService sessions;
    private final WsServer wsServer;
    private final BCryptPasswordEncoder encoder;
    private final LoginRateLimiter perIp;
    private final LoginRateLimiter perUser;
    // A real hash at the configured cost, compared against when the username doesn't exist, so an
    // unknown user costs the same bcrypt time as a wrong password (see login).
    private final String dummyHash;

    AuthService(AuthRepository repo, SessionService sessions, WsServer wsServer, AuthProperties props) {
        this.repo = repo;
        this.sessions = sessions;
        this.wsServer = wsServer;
        this.encoder = new BCryptPasswordEncoder(props.bcryptCost());
        this.perIp = new LoginRateLimiter(props.loginMaxFailuresPerIp(), props.loginWindow(), System::nanoTime);
        this.perUser = new LoginRateLimiter(props.loginMaxFailuresPerUser(), props.loginWindow(), System::nanoTime);
        this.dummyHash = encoder.encode("timing-equalizer");
    }

    NewSession register(String rawUsername, String password) {
        String username = Usernames.normalize(rawUsername);
        if (!Usernames.isValid(username)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Username must be 3-32 characters: a-z, 0-9, _");
        }
        if (password == null || password.length() < MIN_PASSWORD_CHARS || tooLong(password)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Password must be at least " + MIN_PASSWORD_CHARS + " characters and at most " + MAX_PASSWORD_BYTES + " bytes");
        }
        long userId;
        try {
            // Two sign-ups for one name can both get here; the UNIQUE constraint picks the winner.
            userId = repo.insertUser(username, encoder.encode(password));
        } catch (DuplicateKeyException e) {
            // This does reveal that the name exists. Unavoidable for sign-up (the user has to be
            // told to pick another), which is why login is the path that must not leak it.
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Username is taken");
        }
        return sessions.create(userId);
    }

    /**
     * Unknown user and wrong password give the identical 401, and both run one bcrypt comparison,
     * so neither the response nor its timing tells an attacker which usernames exist.
     */
    NewSession login(String rawUsername, String password, String ip) {
        String username = Usernames.normalize(rawUsername);
        // Checked before bcrypt so a blocked attacker can't keep burning our CPU. Keyed on the name
        // as typed (normalized), whether or not it exists, so a 429 doesn't reveal existence either.
        // The per-user limit stops a botnet guessing one account from many IPs; the per-IP limit
        // stops one IP spraying a common password across many accounts. The per-user limit also
        // lets anyone lock a known user out for a window; that's the usual trade-off, and why the
        // window is short rather than a permanent lockout.
        if (perIp.isBlocked(ip) || (username != null && perUser.isBlocked(username))) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Too many failed attempts, try again later");
        }
        Optional<Account> account = Usernames.isValid(username) ? repo.findAccount(username) : Optional.empty();
        boolean passwordOk = checkPassword(password, account.map(Account::passwordHash).orElse(dummyHash));
        if (account.isEmpty() || !passwordOk) {
            perIp.recordFailure(ip);
            if (username != null) {
                perUser.recordFailure(username);
            }
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid username or password");
        }
        perUser.reset(username);
        repo.deleteExpiredSessions(account.get().id());
        return sessions.create(account.get().id());
    }

    /** Ends the session and closes its WebSockets. Idempotent: an unknown token is a no-op. */
    void logout(String rawToken) {
        // Delete first, then close: a socket that connects in between is caught by WsServer's
        // re-check after registration, so no socket can outlive its session.
        sessions.delete(rawToken).ifPresent(wsServer::closeSession);
    }

    // Always exactly one bcrypt comparison, even for inputs we reject, so rejection isn't faster.
    private boolean checkPassword(String password, String hash) {
        if (password == null || tooLong(password)) {
            encoder.matches("timing-equalizer", hash);
            return false;
        }
        return encoder.matches(password, hash);
    }

    private static boolean tooLong(String password) {
        return password.getBytes(StandardCharsets.UTF_8).length > MAX_PASSWORD_BYTES;
    }
}
