package com.orderbook.benchmark;

import com.orderbook.engine.MatchingEngine;
import com.orderbook.engine.OrderBook;
import com.orderbook.model.Order;
import com.orderbook.model.OrderSide;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;

import java.math.BigDecimal;
import java.util.concurrent.TimeUnit;

@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 2)
@Fork(1)
public class OrderBookBenchmark {
    private OrderBook book;
    private MatchingEngine engine;
    private static final BigDecimal PRICE = new BigDecimal("100.00");

    @Setup(Level.Iteration)
    public void setup() {
        book = new OrderBook();
        engine = new MatchingEngine(book, fill -> {});
        for (int i=0; i<1000; i++)
            engine.submit(Order.of(OrderSide.SELL, PRICE, 10));
    }

    @Benchmark
    public void insertOnly() {
        book.addOrder(Order.of(OrderSide.BUY, new BigDecimal("90.00"), 10));
    }

    @Benchmark
    public void matchAndFill() {
        engine.submit(Order.of(OrderSide.BUY, PRICE, 1));
    }

    @Benchmark
    @BenchmarkMode(Mode.Throughput)
    @OutputTimeUnit(TimeUnit.SECONDS)
    public void throughput() {
        engine.submit(Order.of(OrderSide.BUY, PRICE, 1));
    }

    public static void main (String[] args) throws Exception {
        Options opt = new OptionsBuilder()
                .include(OrderBookBenchmark.class.getSimpleName())
                .build();
        new Runner(opt).run();
    }
}
