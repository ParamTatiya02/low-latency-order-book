package com.orderbook.model;

import java.math.BigDecimal;

public record Fill(
        String makerOrderId,
        String takeOrderId,
        BigDecimal price,
        long quantity
) {
}
