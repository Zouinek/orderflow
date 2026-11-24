package com.orderflow.order;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;


public class OrderTest {

    @Test
    void newOrderCanBeCancelled() {
        Order order = new Order();
        order.cancel();
        assertEquals(OrderStatus.CANCELLED, order.getStatus());
    }

    @Test
    void cancelledOrderCannotBeCancelledAgain() {
        Order order = new Order();
        order.cancel();
        assertThrows(OrderNotCancellableException.class, () -> order.cancel());
    }
}
