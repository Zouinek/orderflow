package com.orderflow.order;

import jakarta.validation.constraints.*;

import java.math.BigDecimal;

public record CreateOrderRequest(
        @NotBlank @Size(max = 255) String customerName,
        @NotBlank @Size(max = 255) String item,
        @NotNull @Positive Integer quantity,
        @NotNull @Digits(integer = 10, fraction = 2) @Positive BigDecimal price) {

}
