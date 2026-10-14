package com.example.demo.service;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import com.example.demo.model.MenuItem;
import com.example.demo.model.RestaurantTable;
import com.example.demo.model.User;
import com.example.demo.repository.MenuItemRepository;
import com.example.demo.repository.RestaurantTableRepository;

/** Fills an empty database with a first admin, a demo staff account, a menu and tables. */
@Component
public class StartupData implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(StartupData.class);

    private final UserService userService;
    private final MenuItemRepository menuItems;
    private final RestaurantTableRepository tables;

    public StartupData(UserService userService, MenuItemRepository menuItems,
                       RestaurantTableRepository tables) {
        this.userService = userService;
        this.menuItems = menuItems;
        this.tables = tables;
    }

    @Override
    public void run(String... args) {
        if (userService.createFirstAdminIfNone("admin", "admin123")) {
            userService.create("staff", "staff123", "Demo Staff", User.Role.STAFF);
            log.info("Created admin (admin / admin123) and staff (staff / staff123)");
        }

        if (menuItems.count() == 0) {
            menuItems.save(dish("Chicken Shawarma", "Mains", "28.00", "gluten"));
            menuItems.save(dish("Lamb Kabsa", "Mains", "45.00"));
            menuItems.save(dish("Hummus", "Starters", "15.00", "sesame"));
            menuItems.save(dish("Fattoush Salad", "Starters", "18.00", "gluten"));
            menuItems.save(dish("Karak Tea", "Drinks", "6.00", "milk"));
            menuItems.save(dish("Fresh Lemon Mint", "Drinks", "14.00"));
            log.info("Created sample menu");
        }

        if (tables.count() == 0) {
            for (int number = 1; number <= 5; number++) {
                String code = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
                tables.save(new RestaurantTable(number, code));
            }
        }

        // The code inside each table's QR link, handy for testing.
        tables.findAll().forEach(t ->
                log.info("Table {} QR code: {}", t.getTableNumber(), t.getQrCodeHash()));
    }

    private static MenuItem dish(String name, String category, String price, String... allergens) {
        MenuItem item = new MenuItem(name, category, new BigDecimal(price));
        item.setAllergens(List.of(allergens));
        return item;
    }
}