package com.example.demo.repository;

import java.util.List;

import org.springframework.data.mongodb.repository.MongoRepository;

import com.example.demo.model.MenuItem;

public interface MenuItemRepository extends MongoRepository<MenuItem, String> {

    /** What guests and the chatbot see: only available items, grouped by category. */
    List<MenuItem> findByAvailableTrueOrderByCategoryAscNameAsc();

    /** What the admin sees: every item, including disabled ones. */
    List<MenuItem> findAllByOrderByCategoryAscNameAsc();

    boolean existsByNameIgnoreCase(String name);
}