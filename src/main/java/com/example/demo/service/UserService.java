package com.example.demo.service;

import java.util.List;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import com.example.demo.model.User;
import com.example.demo.model.User.Role;
import com.example.demo.repository.UserRepository;

@Service
public class UserService {

    private final UserRepository users;

    // Hashes passwords and checks them at login. Created here, so no config class is needed.
    private final PasswordEncoder encoder = new BCryptPasswordEncoder();

    public UserService(UserRepository users) {
        this.users = users;
    }

    /** Staff or admin login. Returns the user, or fails with 401. */
    public User login(String username, String password) {
        return users.findByUsername(clean(username))
                .filter(u -> u.isActive()
                        && password != null
                        && encoder.matches(password, u.getPasswordHash()))
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.UNAUTHORIZED, "Wrong username or password"));
    }

    /** Creates a staff or admin account. The password is saved hashed. */
    public User create(String username, String password, String fullName, Role role) {
        String name = clean(username);
        if (name.length() < 3) {
            throw bad("Username must be at least 3 characters");
        }
        if (password == null || password.length() < 6) {
            throw bad("Password must be at least 6 characters");
        }
        if (fullName == null || fullName.isBlank()) {
            throw bad("Full name is required");
        }
        if (users.existsByUsername(name)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Username already taken");
        }
        return users.save(new User(name, encoder.encode(password), fullName.trim(), role));
    }

    public List<User> listByRole(Role role) {
        return users.findByRole(role);
    }

    /** Switches a staff account on or off. Admin accounts cannot be changed here. */
    public User setStaffActive(String id, boolean active) {
        User user = users.findById(id)
                .filter(u -> u.getRole() == Role.STAFF)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Staff member not found"));
        user.setActive(active);
        return users.save(user);
    }

    /**
 * Checks the "Authorization: Basic ..." header and that the user may open the given
 * dashboard section (for example "orders" or "menu"). Pass null for the section to only
 * check the login. Fails with 401 if not logged in, or 403 if the role is not allowed.
 */
    public User authorize(String authorizationHeader, String section) {
    if (authorizationHeader == null || !authorizationHeader.startsWith("Basic ")) {
        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Login required");
    }
    String decoded;
    try {
        decoded = new String(
                Base64.getDecoder().decode(authorizationHeader.substring(6)),
                StandardCharsets.UTF_8);
    } catch (IllegalArgumentException e) {
        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Login required");
    }
    int colon = decoded.indexOf(':');
    if (colon < 0) {
        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Login required");
    }

    User user = login(decoded.substring(0, colon), decoded.substring(colon + 1));
    if (section != null && !user.can(section)) {
        throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You are not allowed to do this");
    }
    return user;
    }

    
    /** Creates the first admin when there are no users yet. Without one, nobody could log in. */
    public boolean createFirstAdminIfNone(String username, String password) {
        if (users.count() > 0) {
            return false;
        }
        create(username, password, "Administrator", Role.ADMIN);
        return true;
    }

    private static String clean(String username) {
        return username == null ? "" : username.trim().toLowerCase();
    }

    private static ResponseStatusException bad(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}