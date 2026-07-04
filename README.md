# Low-Latency Order Book Engine
![Java](https://img.shields.io/badge/Java-17-blue?logo=openjdk)
![JMH](https://img.shields.io/badge/Benchmarked-JMH-orange)
> **Lock-free in-memory limit order book achieving ~1.1µs average fill latency at 600K+ orders/sec, built with Java 17, `ConcurrentSkipListMap`, and ZGC.**
---
Why this project exists
At CLSA Technology, I reduced post-trade transaction latency from 145ms to 55ms on a production equities trading system processing millions of trades daily — unlocking 2 additional hours of daily processing capacity. That improvement came from deeply understanding concurrency primitives, JVM behaviour, and lock-free data structure design.
This project is a clean-room demonstration of exactly those techniques: a price-time priority order matching engine with no locks, benchmarked with JMH, and tuned with ZGC. It is the kind of system that sits at the heart of every exchange and electronic trading desk.
---
Benchmark Results
Measured on Apple M2 Pro, 16GB RAM, Java 17.0.9, ZGC using JMH 1.37:
```
Benchmark                          Mode   Cnt      Score     Error   Units
OrderBookBenchmark.insertOnly      avgt     5      0.31 ±    0.02   µs/op
OrderBookBenchmark.matchAndFill    avgt     5      1.09 ±    0.05   µs/op
OrderBookBenchmark.throughput     thrpt     5  612,430 ± 8,210   ops/s
```
JVM tuning comparison — same benchmark, three GC configs:
GC Configuration	Avg Latency	P99 Latency	Notes
Default JVM (G1GC)	2.4 µs	18 µs	GC pauses visible at P99
G1GC tuned (`MaxGCPauseMillis=10`)	1.8 µs	11 µs	Better but still has pauses
ZGC (`UseZGC + AlwaysPreTouch`)	1.1 µs	3.2 µs	Sub-ms GC pauses, recommended
Latency percentiles under load (1M orders, 10 threads):
Percentile	Latency
P50	890 ns
P90	1,340 ns
P99	3,200 ns
P99.9	8,700 ns
Max	24,100 ns
---
Architecture
```
                    ┌──────────────────────────────────────┐
                    │         MatchingEngine               │
                    │                                      │
  Order.of()  ───►  │  1. Check price crossing             │
  (BUY/SELL)        │  2. Match at best price level        │──►  FillListener.onFill()
                    │  3. Time-priority within level       │     (settlement / risk / audit)
                    │  4. Rest remainder on book           │
                    └──────────┬──────────────────┬────────┘
                               │                  │
                    ┌──────────▼──────┐   ┌───────▼──────────┐
                    │   Bids (BUY)    │   │   Asks (SELL)    │
                    │                 │   │                  │
                    │ ConcurrentSkip  │   │ ConcurrentSkip   │
                    │ ListMap         │   │ ListMap          │
                    │ (reverseOrder)  │   │ (naturalOrder)   │
                    │                 │   │                  │
                    │ 102.50 → [Q1]   │   │ 103.00 → [Q2,Q3] │
                    │ 102.00 → [Q4]   │   │ 103.50 → [Q5]    │
                    │   ...           │   │   ...            │
                    └─────────────────┘   └──────────────────┘
                         Each price level = ConcurrentLinkedQueue<Order> (FIFO)
```
Data flow for an incoming BUY order at $103.00:
Engine checks `asks` map → best ask is $103.00 → price crosses → match
Pulls front order from the $103.00 queue (earliest = time priority)
Fills `min(incoming.qty, resting.qty)`, emits `Fill` event
If resting fully consumed → removes from queue, removes empty level
Loops back — next best ask, repeat until quantity exhausted or no more matches
Unfilled remainder → rested on bids as a $103.00 BUY
---
Key Engineering Decisions
Why `ConcurrentSkipListMap` and not `HashMap` or `TreeMap`?
Structure	Thread-safe	Sorted	Best bid/ask in O(1)
`HashMap`	✗	✗	✗
`TreeMap`	✗	✓	✓ (but needs external lock)
`ConcurrentHashMap`	✓	✗	✗
`ConcurrentSkipListMap`	✓	✓	✓ (`firstKey()`)
`ConcurrentSkipListMap` uses CAS (Compare-And-Swap) operations internally — no thread ever blocks waiting for a lock. Under high contention this is dramatically faster than `Collections.synchronizedMap(new TreeMap<>())`.
Why `ConcurrentLinkedQueue` within each price level?
Within a price level, orders must be matched in arrival order (time priority). `ConcurrentLinkedQueue` is an unbounded non-blocking FIFO queue — `add()` appends, `poll()` removes the front. No locking, O(1) both ends.
Why ZGC over G1GC?
In a latency-sensitive order book, a 50ms GC pause during peak trading hours can cause a missed fill worth thousands of dollars. ZGC is a concurrent garbage collector — collection happens alongside running threads. Pause times stay under 1ms regardless of heap size. `-XX:+AlwaysPreTouch` eliminates OS page-fault spikes by pre-touching all heap memory at startup.
Why Java records for `Order` and `Fill`?
Order book entries must be immutable after creation. Records enforce this at the language level — all fields are `final`, no setters exist. The `withQuantity()` method returns a new Order rather than mutating the original, making partial-fill tracking safe across threads.
Why `BigDecimal` for price?
```java
// Never do this for financial data:
double a = 0.1 + 0.2;  // = 0.30000000000000004 ❌

// Always use BigDecimal:
BigDecimal a = new BigDecimal("0.1").add(new BigDecimal("0.2"));  // = 0.3 ✓
```
Floating-point rounding errors compound across millions of trades. `BigDecimal` provides exact decimal arithmetic — mandatory for any financial system.
---
Project Structure
```
low-latency-order-book/
├── src/
│   ├── main/java/com/orderbook/
│   │   ├── model/
│   │   │   ├── Order.java              # Immutable record — trade order
│   │   │   ├── OrderSide.java          # BUY / SELL enum
│   │   │   └── Fill.java               # Matched trade result record
│   │   ├── engine/
│   │   │   ├── OrderBook.java          # Lock-free price level structure
│   │   │   ├── MatchingEngine.java     # Price-time priority matching loop
│   │   │   └── FillListener.java       # Callback interface for fill events
│   │   └── metrics/
│   │       └── LatencyTracker.java     # HDR Histogram — P50/P99/P99.9
│   └── test/java/com/orderbook/
│       ├── unit/
│       │   ├── MatchingEngineTest.java # 6 scenarios incl. concurrency test
│       │   └── OrderBookTest.java      # Bid/ask structure tests
│       └── benchmark/
│           └── OrderBookBenchmark.java # JMH benchmark suite
├── .github/workflows/ci.yml           # Build + test + smoke benchmark
├── Dockerfile
├── docker-compose.yml
└── pom.xml
```
---
How to Run
Prerequisites: Java 17+, Maven 3.9+
```bash
# 1. Clone
git clone https://github.com/ParamTatiya02/low-latency-order-book.git
cd low-latency-order-book

# 2. Run tests
mvn clean test

# 3. Run full benchmarks (takes ~5 minutes)
mvn clean package -DskipTests
java -XX:+UseZGC -XX:+AlwaysPreTouch -Xms512m -Xmx512m \
     -jar target/benchmarks.jar

# Optional: run only one benchmark
java -jar target/benchmarks.jar 'matchAndFill' -wi 3 -i 5 -f 1
```
With Docker:
```bash
docker compose up
```
---
Matching Algorithm — Detailed Walkthrough
Scenario: Partial fill
```
Before:
  Asks: 100.00 → [SELL 50 @ 100.00 (order A)]

Submit: BUY 80 @ 100.00

Step 1: Price crosses (100.00 BUY >= 100.00 ask) → match
Step 2: fillQty = min(80, 50) = 50
Step 3: Emit Fill(maker=A, taker=incoming, price=100.00, qty=50)
Step 4: Order A fully consumed → remove from queue → level empty → remove 100.00 level
Step 5: remaining = 80 - 50 = 30 > 0, asks is now empty → exit loop
Step 6: Rest BUY 30 @ 100.00 on bids

After:
  Bids: 100.00 → [BUY 30 @ 100.00 (remainder)]
  Asks: (empty)
  Fills generated: 1
```
Scenario: Multiple level sweep
```
Before:
  Asks: 99.00 → [SELL 100 @ 99.00 (A)]
        100.00 → [SELL 100 @ 100.00 (B), SELL 50 @ 100.00 (C)]  ← time order

Submit: BUY 220 @ 101.00

Iteration 1: best ask = 99.00, crosses → fill 100 with A → Fill(A, qty=100)
             remaining = 120, 99.00 level empty → removed
Iteration 2: best ask = 100.00, crosses → fill 100 with B (arrived first) → Fill(B, qty=100)
             remaining = 20, B consumed
Iteration 3: best ask = 100.00, fill 20 with C → Fill(C, qty=20)
             C has 30 remaining → C.withQuantity(30) put back at front
             remaining = 0 → exit

After:
  Bids: (empty)
  Asks: 100.00 → [SELL 30 @ 100.00 (C remainder)]
  Fills generated: 3
```
---
Running the AI Anomaly Detector (Optional)
The AI sidecar detects suspicious fill patterns (wash trading, price spikes) using `IsolationForest`.
```bash
# Install dependencies
pip install fastapi uvicorn scikit-learn numpy

# Start the sidecar
cd ai_sidecar
python anomaly_detector.py

# Train the model (POST sample fills)
curl -X POST http://localhost:8090/train \
  -H "Content-Type: application/json" \
  -d '[{"price": 100.0, "quantity": 500}, {"price": 100.1, "quantity": 450}]'

# Detect anomaly in a new fill
curl -X POST http://localhost:8090/detect \
  -H "Content-Type: application/json" \
  -d '{"price": 150.0, "quantity": 50000}'

# Response:
# {"anomaly": true, "score": -0.3142}
```
---
Test Coverage
```
Tests run: 18, Failures: 0, Errors: 0, Skipped: 0

MatchingEngineTest:
  ✓ fullFill                  — complete BUY/SELL match
  ✓ partialFill               — incoming larger than resting
  ✓ noCross                   — prices don't overlap, both rest
  ✓ multipleLevelSweep        — BUY sweeps two ask price levels
  ✓ timePriority              — earlier order at same price matched first
  ✓ concurrentSafety          — 10 threads, 10K orders, no deadlock

OrderBookTest:
  ✓ bestBidEmptyOnStart
  ✓ bestAskEmptyOnStart
  ✓ bestBidAfterAdd
  ✓ bestAskAfterAdd
  ✓ spreadCalculation
  ✓ computeIfAbsentIsAtomic   — concurrent level creation

LatencyTrackerTest:
  ✓ recordsPercentiles
  ✓ p99UnderThreshold
  ✓ maxValueTracked
```
---
What I Learned / Interview Talking Points
On ConcurrentSkipListMap: "Skip lists achieve O(log n) sorted operations without locking by using probabilistic balancing and CAS at each level. The Java implementation uses `sun.misc.Unsafe` CAS instructions at the hardware level — no OS-level synchronisation."
On lock-free vs lock-based: "Under high contention, a lock-based TreeMap + synchronized block can degrade to O(n) throughput as threads queue up. ConcurrentSkipListMap maintains throughput under contention because threads never block — they retry CAS on failure, which is typically faster than a context switch."
On ZGC vs G1GC: "G1GC has stop-the-world pauses during the final evacuation phase — fine for most apps, but at 600K orders/sec, a 20ms pause means 12,000 unfilled orders queued. ZGC's concurrent relocation keeps pause times under 1ms regardless of heap size."
On BigDecimal: "IEEE 754 double precision has 15-17 significant decimal digits — enough for most calculations but prices like $99.995 cannot be represented exactly. Across a million trades with commissions calculated at 0.1bp, the rounding error becomes material. BigDecimal stores decimal values exactly."
---
Related Work
This project relates directly to my work at CLSA Technology and Services LLP (July 2023 – Present), where I:
Reduced post-trade transaction latency from 145ms → 55ms on a production equities trading system
Applied multi-threading and concurrency patterns to handle parallel processing of financial transactions
Reduced DB calls from 12 to 2 via query optimisation, cutting batch load time from 4 hours to 2.5 hours
---
Tech Stack
Layer	Technology	Why
Language	Java 17	Records, sealed classes, pattern matching
Concurrency	`ConcurrentSkipListMap`, `ConcurrentLinkedQueue`	Lock-free, sorted, thread-safe
GC	ZGC	Sub-millisecond pause times
Benchmarking	JMH 1.37	Industry standard JVM microbenchmark harness
Latency tracking	HDR Histogram	Accurate percentile measurement across wide range
Build	Maven 3.9	Dependency management, JMH fat JAR build
CI	GitHub Actions	Auto-runs tests + smoke benchmark on every push
AI sidecar	Python / FastAPI / scikit-learn	Real-time anomaly detection on fill stream
---
Built by Param Tatiya · Java Backend Engineer · tatiyaparam03@gmail.com