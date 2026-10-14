package com.example.demo.controller;

import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.demo.model.Customer;
import com.example.demo.service.CustomerService;
import com.example.demo.service.UserService;

@RestController
@RequestMapping("/api")
public class CustomerController {

    private final CustomerService customerService;
    private final UserService userService;

    public CustomerController(CustomerService customerService, UserService userService) {
        this.customerService = customerService;
        this.userService = userService;
    }

    /** Guest checkout: finds the customer by phone number, or creates a new profile. */
    @PostMapping("/loyalty/lookup")
    public Customer lookup(@RequestBody Map<String, String> body) {
        return customerService.findOrCreateByPhone(body.get("phoneNumber"));
    }

    /** Admin only: all customers. */
    @GetMapping("/admin/customers")
    public List<Customer> allCustomers(
            @RequestHeader(value = "Authorization", required = false) String auth) {
        userService.authorize(auth, "customers");
        return customerService.listAll();
    }
}