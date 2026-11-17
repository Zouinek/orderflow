package com.orderflow.order;

import java.math.BigDecimal;
import java.time.Instant;

public record OrderResponse(Long id, String customerName, String item, Integer quantity, BigDecimal price,
                            BigDecimal total, Instant createdAt) {
}
