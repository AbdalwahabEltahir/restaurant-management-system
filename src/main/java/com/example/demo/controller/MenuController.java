package com.example.demo.controller;

import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.example.demo.model.MenuItem;
import com.example.demo.service.MenuService;
import com.example.demo.service.UserService;

@RestController
@RequestMapping("/api")
public class MenuController {

    private final MenuService menuService;
    private final UserService userService;

    public MenuController(MenuService menuService, UserService userService) {
        this.menuService = menuService;
        this.userService = userService;
    }

    // ---------- Guest ----------

    /** Available dishes. Send the table code as ?t=CODE; an unknown code gives 404. */
    @GetMapping("/menu")
    public List<MenuItem> menu(@RequestParam(name = "t", required = false) String tableCode) {
        return menuService.availableMenu(tableCode);
    }

    // ---------- Admin (login required, section "menu") ----------

    /** All items, including disabled ones. */
    @GetMapping("/admin/menu-items")
    public List<MenuItem> all(
            @RequestHeader(value = "Authorization", required = false) String auth) {
        userService.authorize(auth, "menu");
        return menuService.allItems();
    }

    /** Body: {"name": "...", "category": "...", "price": 12.50, "ingredients": [...], "allergens": [...]} */
    @PostMapping("/admin/menu-items")
    @ResponseStatus(HttpStatus.CREATED)
    public MenuItem create(
            @RequestHeader(value = "Authorization", required = false) String auth,
            @RequestBody MenuService.Form form) {
        userService.authorize(auth, "menu");
        return menuService.create(form);
    }

    /** Edit an item, including its price. Send the whole item, like when adding it. */
    @PutMapping("/admin/menu-items/{id}")
    public MenuItem update(
            @RequestHeader(value = "Authorization", required = false) String auth,
            @PathVariable String id,
            @RequestBody MenuService.Form form) {
        userService.authorize(auth, "menu");
        return menuService.update(id, form);
    }

    /** Switch an item on or off. Body: {"available": false} */
    @PatchMapping("/admin/menu-items/{id}/available")
    public MenuItem setAvailable(
            @RequestHeader(value = "Authorization", required = false) String auth,
            @PathVariable String id,
            @RequestBody Map<String, Boolean> body) {
        userService.authorize(auth, "menu");
        Boolean available = body.get("available");
        if (available == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Send {\"available\": true} or {\"available\": false}");
        }
        return menuService.setAvailable(id, available);
    }

    /** "Delete" only disables the item, so old orders and the admin list stay intact. */
    @DeleteMapping("/admin/menu-items/{id}")
    public MenuItem disable(
            @RequestHeader(value = "Authorization", required = false) String auth,
            @PathVariable String id) {
        userService.authorize(auth, "menu");
        return menuService.setAvailable(id, false);
    }
}