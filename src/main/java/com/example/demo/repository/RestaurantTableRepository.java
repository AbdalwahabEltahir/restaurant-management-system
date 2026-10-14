package com.example.demo.repository;

import java.util.Optional;

import org.springframework.data.mongodb.repository.MongoRepository;

import com.example.demo.model.RestaurantTable;

public interface RestaurantTableRepository extends MongoRepository<RestaurantTable, String> {

    Optional<RestaurantTable> findByQrCodeHash(String qrCodeHash);
}