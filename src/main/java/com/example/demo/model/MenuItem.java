package com.example.demo.model;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

@Document("menu_items")
public class MenuItem {

    @Id
    private String id;

    private String name;
    private String category;
    private BigDecimal price;
    private List<String> ingredients = new ArrayList<>();
    private List<String> allergens = new ArrayList<>();

    /** False means the item is disabled: hidden from guests and the chatbot. */
    @Field("isAvailable")
    private boolean available = true;

    public MenuItem() {
    }

    public MenuItem(String name, String category, BigDecimal price) {
        this.name = name;
        this.category = category;
        this.price = price;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

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

    public boolean isAvailable() { return available; }
    public void setAvailable(boolean available) { this.available = available; }
}