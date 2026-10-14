package com.example.demo.controller;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.demo.model.RestaurantTable;
import com.example.demo.repository.RestaurantTableRepository;
import com.example.demo.service.UserService;

@RestController
@RequestMapping("/api/staff")
public class TableController {

    private final RestaurantTableRepository tables;
    private final UserService userService;

    public TableController(RestaurantTableRepository tables, UserService userService) {
        this.tables = tables;
        this.userService = userService;
    }

    /** Staff only: all tables with their numbers and QR codes. */
    @GetMapping("/tables")
    public List<RestaurantTable> allTables(
            @RequestHeader(value = "Authorization", required = false) String auth) {
        userService.authorize(auth, "orders");
        return tables.findAll();
    }
}