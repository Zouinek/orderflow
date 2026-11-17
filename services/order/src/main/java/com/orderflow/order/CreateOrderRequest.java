package com.orderflow.order;

import java.math.BigDecimal;

public record CreateOrderRequest(String customerName, String item, Integer quantity, BigDecimal price) {

}
