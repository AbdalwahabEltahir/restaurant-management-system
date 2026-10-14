package com.example.demo.controller;

import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.example.demo.model.User;
import com.example.demo.service.UserService;

@RestController
@RequestMapping("/api")
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    /** Staff or admin login. Body: {"username": "...", "password": "..."} */
    @PostMapping("/auth/login")
    public Map<String, Object> login(@RequestBody Map<String, String> body) {
        return profile(userService.login(body.get("username"), body.get("password")));
    }

    /** Who is logged in? Uses the saved credentials in the Authorization header. */
    @GetMapping("/auth/me")
    public Map<String, Object> me(
            @RequestHeader(value = "Authorization", required = false) String auth) {
        return profile(userService.authorize(auth, null));
    }

    /** Admin creates a staff account. Body: {"username", "password", "fullName"} */
    @PostMapping("/admin/staff")
    @ResponseStatus(HttpStatus.CREATED)
    public User createStaff(
            @RequestHeader(value = "Authorization", required = false) String auth,
            @RequestBody Map<String, String> body) {
        userService.authorize(auth, "staff");
        return userService.create(body.get("username"), body.get("password"),
                body.get("fullName"), User.Role.STAFF);
    }

    @GetMapping("/admin/staff")
    public List<User> listStaff(
            @RequestHeader(value = "Authorization", required = false) String auth) {
        userService.authorize(auth, "staff");
        return userService.listByRole(User.Role.STAFF);
    }

    /** Admin switches a staff account on or off. Body: {"active": false} */
    @PatchMapping("/admin/staff/{id}/active")
    public User setStaffActive(
            @RequestHeader(value = "Authorization", required = false) String auth,
            @PathVariable String id,
            @RequestBody Map<String, Boolean> body) {
        userService.authorize(auth, "staff");
        Boolean active = body.get("active");
        if (active == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Send {\"active\": true} or {\"active\": false}");
        }
        return userService.setStaffActive(id, active);
    }

    private static Map<String, Object> profile(User user) {
        return Map.of(
                "id", user.getId(),
                "username", user.getUsername(),
                "fullName", user.getFullName(),
                "role", user.getRole().name(),
                "dashboardSections", user.getRole().getDashboardSections());
    }
}