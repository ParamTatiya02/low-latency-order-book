package com.orderbook.engine;

import com.orderbook.model.Order;
import com.orderbook.model.OrderSide;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.Optional;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentSkipListMap;

public class OrderBook {
    private final ConcurrentSkipListMap<BigDecimal, ConcurrentLinkedQueue<Order>>
            bids = new ConcurrentSkipListMap<>(Comparator.reverseOrder());

    private final ConcurrentSkipListMap<BigDecimal, ConcurrentLinkedQueue<Order>>
            asks = new ConcurrentSkipListMap<>();

    public void addOrder(Order order){
        var levels = order.side() == OrderSide.BUY ? bids : asks;
        levels.computeIfAbsent(order.price(), k -> new ConcurrentLinkedQueue<>()).add(order);
    }

    public Optional<BigDecimal> bestBid() {
        return bids.isEmpty() ? Optional.empty() : Optional.of(bids.firstKey());
    }

    public Optional<BigDecimal> bestAsk() {
        return asks.isEmpty() ? Optional.empty() : Optional.of(asks.firstKey());
    }

    public Optional<BigDecimal> spread() {
        return bestBid().flatMap(b -> bestAsk().map(a -> a.subtract(b)));
    }

    ConcurrentSkipListMap<BigDecimal, ConcurrentLinkedQueue<Order>> bids() {
        return bids;
    }

    ConcurrentSkipListMap<BigDecimal, ConcurrentLinkedQueue<Order>> asks() {
        return asks;
    }
}
