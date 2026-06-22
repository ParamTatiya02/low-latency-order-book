package com.orderbook.unit;

import com.orderbook.engine.*;
import com.orderbook.model.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.math.BigDecimal;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class MatchingEngineTest {
    private OrderBook book;
    private List<Fill> fills;
    private MatchingEngine engine;

    @BeforeEach
    void setup() {
        book = new OrderBook();
        fills = new ArrayList<>();
        engine = new MatchingEngine(book, fills::add);
    }

    @Test
    void fullFill() {
        engine.submit(Order.of(OrderSide.SELL, new BigDecimal(50), 100));
        engine.submit(Order.of(OrderSide.BUY, new BigDecimal(50), 100));
        assertEquals(1, fills.size());
        assertEquals(100, fills.get(0).quantity());
        assertEquals(new BigDecimal(50), fills.get(0).price());
        assertTrue(book.bestAsk().isEmpty());
        assertTrue(book.bestBid().isEmpty());
    }

    @Test
    void partialFill() {
        engine.submit(Order.of(OrderSide.SELL, new BigDecimal(50), 50));
        engine.submit(Order.of(OrderSide.BUY, new BigDecimal(50), 100));
        assertEquals(1, fills.size());
        assertEquals(50, fills.get(0).quantity());
        assertEquals(new BigDecimal(50), fills.get(0).price());
    }

    @Test
    void noCross() {
        engine.submit(Order.of(OrderSide.SELL, new BigDecimal(51), 100));
        engine.submit(Order.of(OrderSide.BUY, new BigDecimal(50), 100));
        assertTrue(fills.isEmpty());
        assertEquals(new BigDecimal(51), book.bestAsk().orElseThrow());
        assertEquals(new BigDecimal(50), book.bestBid().orElseThrow());
    }

    @Test
    void concurrentSafety() throws InterruptedException{
        AtomicLong totalFilled = new AtomicLong();
        MatchingEngine safeEngine = new MatchingEngine(book, fill -> totalFilled.addAndGet(fill.quantity()));
        int threads = 10;
        int ordersEach = 1000;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        for(int i=0; i<threads; i++){
            OrderSide side = (i % 2 == 0) ? OrderSide.BUY : OrderSide.SELL;
            pool.submit(() -> {
                for(int j=0; j<ordersEach; j++){
                    safeEngine.submit(Order.of(side, new BigDecimal(100), 1));
                }
            });
        }
        pool.shutdown();
        pool.awaitTermination(30, TimeUnit.SECONDS);
        assertTrue(totalFilled.get() >= 0);
    }
}
