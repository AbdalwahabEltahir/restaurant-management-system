package com.example.demo.model;


import java.time.Instant;
import java.util.List;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import com.fasterxml.jackson.annotation.JsonIgnore;

@Document("users")
public class User {

    /** Each role lists the dashboard sections it may open. Admin gets more. */
    public enum Role {
        STAFF(List.of("orders")),
        ADMIN(List.of("orders", "menu", "staff", "customers", "reports"));

        private final List<String> dashboardSections;

        Role(List<String> dashboardSections) {
            this.dashboardSections = dashboardSections;
        }

        public List<String> getDashboardSections() {
            return dashboardSections;
        }
    }

    @Id
    private String id;

    @Indexed(unique = true)
    private String username;

    @JsonIgnore
    private String passwordHash;

    private String fullName;
    private Role role;
    private boolean active = true;
    private Instant createdAt = Instant.now();

    public User() {
    }

    public User(String username, String passwordHash, String fullName, Role role) {
        this.username = username;
        this.passwordHash = passwordHash;
        this.fullName = fullName;
        this.role = role;
    }

    /** True if this user may open the given dashboard section, e.g. can("menu"). */
    public boolean can(String section) {
        return active && role.getDashboardSections().contains(section);
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }

    public String getPasswordHash() { return passwordHash; }
    public void setPasswordHash(String passwordHash) { this.passwordHash = passwordHash; }

    public String getFullName() { return fullName; }
    public void setFullName(String fullName) { this.fullName = fullName; }

    public Role getRole() { return role; }
    public void setRole(Role role) { this.role = role; }

    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}