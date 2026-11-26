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
    void cancellingTwiceKeepsOrderCancelled() {
        Order order = new Order();
        order.cancel();
        order.cancel();
        assertEquals(OrderStatus.CANCELLED, order.getStatus());
    }

    @Test
    void confirmedOrderCannotBeCancelled() {
        Order order = new Order();
        order.confirm();
        assertThrows(OrderNotCancellableException.class, () -> order.cancel());
    }

    @Test
    void cancelledOrderCannotBeConfirmed() {
        Order order = new Order();
        order.cancel();
        assertThrows(IllegalStateException.class, () -> order.confirm());
    }

}
