package com.orderflow.order;

import org.springframework.data.jpa.repository.JpaRepository;

// Spring Data JPA generates the implementation at runtime:
// findAll(), findById(), save(), deleteById() ... all for free
public interface OrderRepository extends JpaRepository<Order, Long> {
}
