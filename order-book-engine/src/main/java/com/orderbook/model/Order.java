package com.orderbook.model;

import java.math.BigDecimal;
import java.util.UUID;

public record Order(
        String orderId,
        OrderSide side,
        BigDecimal price,
        long quantity,
        long timestampNanos
) {

    public static Order of(OrderSide side, BigDecimal price, long qty){
        return new Order(UUID.randomUUID().toString(), side, price, qty, System.nanoTime());
    }

    public Order withQuantity(long newQty){
        return new Order(orderId, side, price, newQty, timestampNanos);
    }
}
