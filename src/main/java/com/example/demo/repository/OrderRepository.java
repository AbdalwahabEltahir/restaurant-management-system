package com.example.demo.repository;



import java.util.Collection;
import java.util.List;

import org.springframework.data.mongodb.repository.MongoRepository;

import com.example.demo.model.Order;

public interface OrderRepository extends MongoRepository<Order, String> {

    List<Order> findByTableIdAndStatusIn(String tableId, Collection<Order.Status> statuses);

    List<Order> findByStatusInOrderByCreatedAtAsc(Collection<Order.Status> statuses);
}
