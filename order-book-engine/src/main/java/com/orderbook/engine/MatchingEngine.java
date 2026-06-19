package com.orderbook.engine;

import com.orderbook.model.*;

import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;

public class MatchingEngine {
    private final OrderBook book;
    private final FillListener fillListener;

    public MatchingEngine(OrderBook book, FillListener fillListener) {
        this.book = book;
        this.fillListener = fillListener;
    }

    public List<Fill> submit(Order incoming){
        var fills = new ArrayList<Fill>();
        long remaining = incoming.quantity();
        var opposite = incoming.side() == OrderSide.BUY ? book.asks() : book.bids();

        while (remaining > 0 && !opposite.isEmpty()) {
            var bestEntry = opposite.firstEntry();
            BigDecimal bestPrice = bestEntry.getKey();

            boolean crosses = incoming.side() == OrderSide.BUY ? incoming.price().compareTo(bestPrice) >= 0
                    : incoming.price().compareTo(bestPrice) <= 0;

            if(!crosses) break;
            ConcurrentLinkedQueue<Order> level = bestEntry.getValue();
            Order resting  = level.peek();
            if (resting == null) {
                opposite.remove(bestPrice);
                continue;
            }

            long fillQty = Math.min(remaining, resting.quantity());
            Fill fill = new Fill(resting.orderId(), incoming.orderId(), bestPrice, fillQty);
            fills.add(fill);
            fillListener.onFill(fill);
            if (fillQty == resting.quantity()) {
                level.poll();
                if (level.isEmpty()) {
                    opposite.remove(bestPrice);
                }
            } else {
                Order updatedResting = resting.withQuantity(resting.quantity() - fillQty);
                level.poll();
                level.add(updatedResting);
            }
            remaining -= fillQty;
        }
        if (remaining > 0) {
            Order remainingOrder = incoming.withQuantity(remaining);
            book.addOrder(remainingOrder);
        }
        return fills;
    }
}
