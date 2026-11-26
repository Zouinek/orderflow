package com.orderflow.order;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;

// "orders" because ORDER is a reserved word in SQL — the default table name would break MySQL
@Entity
@Table(name = "orders")
public class Order {

    @Version
    private Long version;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String customerName;
    private String item;
    private int quantity;
    private BigDecimal price;
    private Instant createdAt = Instant.now();

    @Enumerated(EnumType.STRING)
    private OrderStatus status = OrderStatus.CREATED;

    public Long getId() { return id; }

    public String getCustomerName() { return customerName; }
    public void setCustomerName(String customerName) { this.customerName = customerName; }

    public String getItem() { return item; }
    public void setItem(String item) { this.item = item; }

    public int getQuantity() { return quantity; }
    public void setQuantity(int quantity) { this.quantity = quantity; }

    public BigDecimal getPrice() { return price; }
    public void setPrice(BigDecimal price) { this.price = price; }

    public Instant getCreatedAt() { return createdAt; }

    public OrderStatus getStatus() { return status; }

    public void cancel() {
         if (status != OrderStatus.CREATED) {
            throw new OrderNotCancellableException(id, status);
         }
        status = OrderStatus.CANCELLED;
    }


}
