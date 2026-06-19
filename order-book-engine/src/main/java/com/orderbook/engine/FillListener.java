package com.orderbook.engine;

import com.orderbook.model.Fill;

public interface FillListener {
    void onFill(Fill fill);
}
