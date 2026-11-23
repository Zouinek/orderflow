package com.orderflow.order;

import jakarta.validation.constraints.*;

import java.math.BigDecimal;

public record CreateOrderRequest(
        @NotBlank String customerName,
        @NotBlank String item,
        @NotNull @Positive Integer quantity,
        @NotNull @DecimalMin("0") BigDecimal price) {

}
