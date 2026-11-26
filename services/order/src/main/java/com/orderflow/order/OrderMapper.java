package com.orderflow.order;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;

@Component
public class OrderMapper {

    public Order toEntity(CreateOrderRequest createOrderRequest) {

        Order order = new Order();

        order.setCustomerName(createOrderRequest.customerName());
        order.setItem(createOrderRequest.item());
        order.setQuantity(createOrderRequest.quantity());
        order.setPrice(createOrderRequest.price());

        return order;
    }

    public OrderResponse toResponse(Order order) {

        BigDecimal total = order.getPrice().multiply(BigDecimal.valueOf(order.getQuantity()));

        return new OrderResponse(
                order.getId(),
                order.getCustomerName(),
                order.getItem(),
                order.getQuantity(),
                order.getPrice(),
                total,
                order.getCreatedAt(),
                order.getStatus()
        );
    }
}
