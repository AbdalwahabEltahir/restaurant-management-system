   package com.example.demo.repository;


import java.util.Optional;

import org.springframework.data.mongodb.repository.MongoRepository;

import com.example.demo.model.Customer;

public interface CustomerRepository extends MongoRepository<Customer, String> {

    Optional<Customer> findByPhoneNumber(String phoneNumber);
}