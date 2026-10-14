package com.example.demo.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import com.example.demo.model.MenuItem;
import com.example.demo.repository.MenuItemRepository;
import com.example.demo.repository.RestaurantTableRepository;

@Service
public class MenuService {

    private final MenuItemRepository menuItems;
    private final RestaurantTableRepository tables;

    public MenuService(MenuItemRepository menuItems, RestaurantTableRepository tables) {
        this.menuItems = menuItems;
        this.tables = tables;
    }

    /** The details an admin sends to add or edit a menu item. */
    public static class Form {
        private String name;
        private String category;
        private BigDecimal price;
        private List<String> ingredients;
        private List<String> allergens;
        private Boolean available;   

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }

        public String getCategory() { return category; }
        public void setCategory(String category) { this.category = category; }

        public BigDecimal getPrice() { return price; }
        public void setPrice(BigDecimal price) { this.price = price; }

        public List<String> getIngredients() { return ingredients; }
        public void setIngredients(List<String> ingredients) { this.ingredients = ingredients; }

        public List<String> getAllergens() { return allergens; }
        public void setAllergens(List<String> allergens) { this.allergens = allergens; }

        public Boolean getAvailable() { return available; }
        public void setAvailable(Boolean available) { this.available = available; }
    }

    // ---------- Guests and the chatbot ----------

    /**
     * The menu a guest sees. If a table code is given it must be a real table (404 if not).
     * Disabled items are never returned.
     */
    public List<MenuItem> availableMenu(String qrCodeHash) {
        if (qrCodeHash != null) {
            tables.findByQrCodeHash(qrCodeHash)
                    .orElseThrow(() -> new ResponseStatusException(
                            HttpStatus.NOT_FOUND, "Unknown table code"));
        }
        return menuItems.findByAvailableTrueOrderByCategoryAscNameAsc();
    }

    /** The same list for the chatbot, which has no table code of its own to check here. */
    public List<MenuItem> availableMenu() {
        return availableMenu(null);
    }

    // ---------- Admin ----------

    public List<MenuItem> allItems() {
        return menuItems.findAllByOrderByCategoryAscNameAsc();
    }

    public MenuItem create(Form form) {
        check(form);
        String name = form.getName().trim();
        if (menuItems.existsByNameIgnoreCase(name)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "A menu item with this name already exists");
        }
        MenuItem item = new MenuItem();
        apply(item, form);
        return menuItems.save(item);
    }

    /** Edits every field, including the price. Send the whole item. */
    public MenuItem update(String id, Form form) {
        MenuItem item = getById(id);
        check(form);
        String name = form.getName().trim();
        if (!item.getName().equalsIgnoreCase(name) && menuItems.existsByNameIgnoreCase(name)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "A menu item with this name already exists");
        }
        apply(item, form);
        return menuItems.save(item);
    }

    /** Switches an item on or off. Off means hidden from guests and the chatbot. */
    public MenuItem setAvailable(String id, boolean available) {
        MenuItem item = getById(id);
        item.setAvailable(available);
        return menuItems.save(item);
    }

    // ---------- Helpers ----------

    private MenuItem getById(String id) {
        return menuItems.findById(id)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Menu item not found"));
    }

    private static void check(Form form) {
        if (form == null) {
            throw bad("Send the menu item details");
        }
        if (form.getName() == null || form.getName().isBlank()) {
            throw bad("Name is required");
        }
        if (form.getName().trim().length() > 100) {
            throw bad("Name is too long (100 characters at most)");
        }
        if (form.getCategory() == null || form.getCategory().isBlank()) {
            throw bad("Category is required");
        }
    }

    private static void apply(MenuItem item, Form form) {
        item.setName(form.getName().trim());
        item.setCategory(form.getCategory().trim());
        item.setPrice(money(form.getPrice()));
        item.setIngredients(clean(form.getIngredients()));
        item.setAllergens(clean(form.getAllergens()));
        if (form.getAvailable() != null) {
            item.setAvailable(form.getAvailable());
        }
    }

    private static BigDecimal money(BigDecimal price) {
        if (price == null || price.signum() <= 0) {
            throw bad("Price must be more than 0");
        }
        if (price.compareTo(new BigDecimal("100000")) > 0) {
            throw bad("Price is too high");
        }
        try {
            return price.setScale(2, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException e) {
            throw bad("Price can have at most 2 decimal places");
        }
    }

    private static List<String> clean(List<String> values) {
        List<String> result = new ArrayList<>();
        if (values != null) {
            for (String value : values) {
                if (value != null && !value.isBlank()) {
                    result.add(value.trim());
                }
            }
        }
        return result;
    }

    private static ResponseStatusException bad(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}