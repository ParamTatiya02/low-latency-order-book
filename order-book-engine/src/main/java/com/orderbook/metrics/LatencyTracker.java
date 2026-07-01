package com.orderbook.metrics;

import org.HdrHistogram.Histogram;

import java.util.concurrent.TimeUnit;

public class LatencyTracker {
    private final Histogram histogram = new Histogram(TimeUnit.SECONDS.toNanos(10),3);

    public void record(long startNanos) {
        histogram.recordValue(System.nanoTime() - startNanos);
    }

    public void printReport() {
        System.out.printf("P50 : %,d ns%n", histogram.getValueAtPercentile(50));
        System.out.printf("P90 : %,d ns%n", histogram.getValueAtPercentile(90));
        System.out.printf("P99 : %,d ns%n", histogram.getValueAtPercentile(99));
        System.out.printf("P99.9 : %,d ns%n", histogram.getValueAtPercentile(99.9));
        System.out.printf("Max : %,d ns%n", histogram.getMaxValue());
    }
}
