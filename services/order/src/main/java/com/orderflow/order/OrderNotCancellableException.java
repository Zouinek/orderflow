package com.orderflow.order;

public class OrderNotCancellableException extends RuntimeException {
    public OrderNotCancellableException(Long orderId, OrderStatus status) {
        super("Order " + orderId + " cannot be cancelled: it is already " + status);
    }
}
