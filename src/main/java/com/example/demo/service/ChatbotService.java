package com.example.demo.service;

import com.example.demo.model.MenuItem;
import com.example.demo.model.Order;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// A simple rule-based chatbot. It has no AI service behind it: it reads the
// available menu items (through MenuService, so disabled items never appear)
// and answers by looking for words from the menu inside the guest's question.
// For "where is my order" it reads the open orders of the guest's own table only.
// A dish with an empty allergen list has no allergy information: the bot says it is
// not sure and sends the guest to the staff. The admin types "none" for a dish that
// really has no allergens.
@Service
public class ChatbotService {

    private static final Pattern MAX_PRICE =
            Pattern.compile("(under|below|less than|up to|maximum|max) (\\d+(\\.\\d+)?)");

    private final MenuService menuService;
    private final OrderService orderService;

    public ChatbotService(MenuService menuService, OrderService orderService) {
        this.menuService = menuService;
        this.orderService = orderService;
    }

    public String answer(String qrCodeHash, String message) {
        if (message == null || message.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Please type a question");
        }
        if (message.length() > 300) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The question is too long");
        }

        // Only the dishes of this table's menu that are available.
        List<MenuItem> dishes = menuService.availableMenu(qrCodeHash);
        if (dishes.isEmpty()) {
            return "The menu is empty right now.";
        }
        String q = message.toLowerCase();

        // 0. Where is my order? Only the orders of this table (FR-2.2).
        if (containsAny(q, "my order", "order status", "where is my", "where's my", "my food",
                "how long", "ready yet", "is it ready", "status")) {
            return orderStatus(qrCodeHash);
        }
        if (containsAny(q, "want to order", "can i order", "how do i order", "place an order", "to order")) {
            return "To order, please tell a member of our staff what you would like.";
        }

        // 1. A dish name in the question: describe that dish.
        List<String> described = new ArrayList<>();
        for (MenuItem dish : dishes) {
            if (q.contains(dish.getName().toLowerCase())) {
                described.add(describe(dish));
            }
        }
        if (!described.isEmpty()) {
            return String.join("\n", described);
        }

        // 2. Otherwise narrow the list down with every clue found in the question.
        List<MenuItem> pool = new ArrayList<>(dishes);
        boolean filtered = false;

        // A category such as "desserts" (only the first one found is used).
        for (String category : categories(dishes)) {
            if (mentions(q, category.toLowerCase())) {
                pool.removeIf(d -> !category.equalsIgnoreCase(d.getCategory()));
                filtered = true;
                break;
            }
        }

        // Allergens and ingredients. "nut free" or "without nuts" keeps the dishes
        // that do NOT have it. "with nuts" keeps the dishes that DO have it.
        List<String> askedAllergens = new ArrayList<>();
        for (String allergen : allergens(dishes)) {
            if (mentions(q, allergen)) {
                askedAllergens.add(allergen);
            }
        }
        List<String> askedIngredients = new ArrayList<>();
        for (String ingredient : ingredients(dishes)) {
            if (ingredient.length() >= 3 && mentions(q, ingredient)) {
                askedIngredients.add(ingredient);
            }
        }
        boolean wantsSafe = containsAny(q, "free", "without", "no ", "avoid", "allergic", "safe",
                "can't", "cannot", "don't", "dont");
        List<String> notSure = new ArrayList<>();      // dishes with no allergy information
        if (!askedAllergens.isEmpty() || !askedIngredients.isEmpty()) {
            if (!askedAllergens.isEmpty()) {
                for (MenuItem dish : pool) {
                    if (!hasAllergyInfo(dish)) notSure.add(dish.getName());
                }
            }
            pool.removeIf(d -> {
                if (!askedAllergens.isEmpty() && !hasAllergyInfo(d)) {
                    return true;             // not sure about it, so never list it as safe or unsafe
                }
                boolean has = hasAny(d.getAllergens(), askedAllergens)
                        || hasAny(d.getIngredients(), askedIngredients);
                return has == wantsSafe;     // remove the ones that do not fit the question
            });
            filtered = true;
        } else if (q.contains("allerg")) {
            return "Allergens on our menu: " + String.join(", ", allergens(dishes))
                    + ". Ask for example: Which dishes are nut free? Please also tell our staff about any allergy.";
        }

        // A price limit such as "under 30".
        Matcher matcher = MAX_PRICE.matcher(q);
        if (matcher.find()) {
            BigDecimal max = new BigDecimal(matcher.group(2));
            pool.removeIf(d -> d.getPrice().compareTo(max) > 0);
            filtered = true;
        }

        // Cheapest or most expensive of what is left.
        Comparator<MenuItem> byPrice = Comparator.comparing(MenuItem::getPrice);
        boolean cheapest = q.contains("cheap");
        boolean priciest = q.contains("expensive") || q.contains("priciest");
        if ((cheapest || priciest) && !pool.isEmpty()) {
            MenuItem pick = cheapest ? Collections.min(pool, byPrice) : Collections.max(pool, byPrice);
            pool = new ArrayList<>(List.of(pick));
            filtered = true;
        }

        if (filtered) {
            StringBuilder text = new StringBuilder();
            if (pool.isEmpty()) {
                text.append("Sorry, I could not find a dish for that on today's menu.");
            } else {
                text.append("These dishes match:");
                for (MenuItem dish : pool) {
                    text.append("\n- ").append(dish.getName())
                        .append(" (").append(dish.getPrice().toPlainString()).append(")");
                }
            }
            if (!notSure.isEmpty()) {
                text.append("\nI am not sure about the allergens of: ")
                    .append(String.join(", ", notSure)).append(". Please ask our staff.");
            }
            if (!askedAllergens.isEmpty()) {
                text.append("\nPlease also tell our staff about any allergy.");
            }
            return text.toString();
        }

        // 3. A general question about the menu: list it by category.
        if (containsAny(q, "menu", "have", "serve", "recommend", "suggest", "popular", "food", "eat")) {
            Map<String, List<String>> byCategory = new LinkedHashMap<>();
            for (MenuItem dish : dishes) {
                byCategory.computeIfAbsent(dish.getCategory(), key -> new ArrayList<>()).add(dish.getName());
            }
            StringBuilder text = new StringBuilder("Today's menu:");
            for (Map.Entry<String, List<String>> entry : byCategory.entrySet()) {
                text.append("\n").append(entry.getKey()).append(": ").append(String.join(", ", entry.getValue()));
            }
            return text.toString();
        }

        return "I can answer questions about today's menu. Try: What is on the menu? "
                + "Which dishes are nut free? What desserts do you have? What is the cheapest dish? "
                + "Or type the name of a dish.";
    }

    private static String describe(MenuItem dish) {
        StringBuilder text = new StringBuilder(dish.getName())
                .append(" costs ").append(dish.getPrice().toPlainString()).append(".");
        List<String> ingredients = list(dish.getIngredients());
        if (!ingredients.isEmpty()) {
            text.append(" Ingredients: ").append(String.join(", ", ingredients)).append(".");
        }
        if (!hasAllergyInfo(dish)) {
            text.append(" I am not sure about the allergens of this dish. Please ask our staff.");
        } else if (allergenNames(dish).isEmpty()) {
            text.append(" It has no allergens.");
        } else {
            text.append(" Contains: ").append(String.join(", ", allergenNames(dish))).append(".");
        }
        return text.toString();
    }

    // Where is my order? Reads the open orders of the table that belongs to the QR code.
    private String orderStatus(String qrCodeHash) {
        List<Order> orders = orderService.openOrdersForTable(qrCodeHash);
        if (orders.isEmpty()) {
            return "There are no open orders at your table right now.";
        }
        StringBuilder text = new StringBuilder("Your orders:");
        int number = 1;
        for (Order order : orders) {
            String status = String.valueOf(order.getStatus());
            String meaning;
            switch (status) {
                case "PENDING":   meaning = "received, waiting for the kitchen"; break;
                case "PREPARING": meaning = "being prepared"; break;
                case "READY":     meaning = "ready, it will be brought to you"; break;
                case "SERVED":    meaning = "served"; break;
                default:          meaning = status.toLowerCase();
            }
            List<String> lines = new ArrayList<>();
            for (Order.Item item : order.getItems()) {
                lines.add(item.getQuantity() + " x " + item.getName());
            }
            text.append("\nOrder ").append(number++).append(": ").append(meaning)
                .append(" (").append(String.join(", ", lines)).append(")");
        }
        return text.toString();
    }

    // A dish has allergy information when its allergen list is not empty.
    // The admin types "none" for a dish that has no allergens at all.
    private static boolean hasAllergyInfo(MenuItem dish) {
        return !list(dish.getAllergens()).isEmpty();
    }

    // The real allergen names of a dish (the word "none" is not an allergen).
    private static List<String> allergenNames(MenuItem dish) {
        List<String> names = new ArrayList<>();
        for (String a : list(dish.getAllergens())) {
            if (!a.isBlank() && !a.trim().equalsIgnoreCase("none")) names.add(a.trim());
        }
        return names;
    }

    private static Set<String> categories(List<MenuItem> dishes) {
        Set<String> result = new LinkedHashSet<>();
        for (MenuItem dish : dishes) {
            if (dish.getCategory() != null) result.add(dish.getCategory());
        }
        return result;
    }

    private static Set<String> allergens(List<MenuItem> dishes) {
        Set<String> result = new TreeSet<>();
        for (MenuItem dish : dishes) {
            for (String a : allergenNames(dish)) result.add(a.toLowerCase());
        }
        return result;
    }

    private static Set<String> ingredients(List<MenuItem> dishes) {
        Set<String> result = new TreeSet<>();
        for (MenuItem dish : dishes) {
            for (String i : list(dish.getIngredients())) result.add(i.toLowerCase().trim());
        }
        return result;
    }

    private static List<String> list(List<String> values) {
        return values == null ? List.of() : values;
    }

    private static boolean hasAny(List<String> values, List<String> wanted) {
        for (String value : list(values)) {
            if (wanted.contains(value.toLowerCase().trim())) return true;
        }
        return false;
    }

    // True when the question has the word, also when the guest wrote the singular ("nut" for "nuts").
    private static boolean mentions(String q, String word) {
        if (word.isBlank()) return false;
        return q.contains(word)
                || (word.length() > 3 && word.endsWith("s") && q.contains(word.substring(0, word.length() - 1)));
    }

    private static boolean containsAny(String q, String... words) {
        for (String word : words) {
            if (q.contains(word)) return true;
        }
        return false;
    }
}