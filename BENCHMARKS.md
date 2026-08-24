# Frame time, staleness and CPU — TickGrid against TableView

Intel HD 620 laptop, JDK 21.0.11, JavaFX 21.0.5, D3D pipeline, 1180x720 window (~45 rows visible).
3s warmup + 5s measured per rate, each implementation in a fresh JVM. `./bench.sh`

Three implementations, same feed, same columns, same fixed row height:

- **tickgrid** — conflating ingress, canvas renderer.
- **tableview-batched** — `TableView` over an `ObservableList`, updates parked in a lock-free queue
  and applied on the pulse. What a careful developer writes. No conflation.
- **tableview-naive** — one `Platform.runLater` per message. What the design document describes,
  and what most people write first.

Plus **nullsink**, which consumes the feed and discards it. Without that baseline a CPU comparison
at high rates is mostly the feed measuring itself.

## 5,000 instruments

| rate | impl | fps | p99 ms | dropped | stale p50 | floor | cores | net cores | conflation |
|---|---|---|---|---|---|---|---|---|---|
| 10k | nullsink | 61.5 | 31.9 | 2.9% | – | – | 0.02 | – | – |
| 10k | tickgrid | 59.7 | 31.7 | 5.4% | 250.9 | 250.0 | 0.30 | 0.28 | 1.0:1 |
| 10k | tv-batched | 61.9 | 35.5 | 4.5% | 249.7 | 250.0 | 0.55 | 0.53 | – |
| 10k | tv-naive | 63.5 | 30.7 | 18.6% | 251.5 | 250.0 | 0.86 | 0.84 | – |
| 50k | nullsink | 62.3 | 16.9 | 0.0% | – | – | 0.04 | – | – |
| 50k | tickgrid | 59.9 | 32.3 | 4.0% | 49.9 | 50.0 | 0.27 | 0.23 | 1.0:1 |
| 50k | tv-batched | 72.8 | 17.0 | 0.3% | 49.8 | 50.0 | 0.27 | 0.23 | – |
| 50k | **tv-naive** | **0.4** | **2367** | **100%** | **1836** | 50.0 | 1.41 | – | – |
| 100k | nullsink | 62.3 | 17.0 | 0.0% | – | – | 0.10 | – | – |
| 100k | tickgrid | 59.7 | 32.5 | 4.0% | 24.6 | 25.0 | 0.45 | 0.35 | 1.0:1 |
| 100k | tv-batched | 82.1 | 17.1 | 0.2% | 24.9 | 25.0 | 0.38 | 0.28 | – |
| 100k | **tv-naive** | **0.0** | – | – | **10050** | 25.0 | 2.60 | – | – |
| 250k | nullsink | 62.1 | 25.2 | 1.6% | – | – | 2.00 | – | – |
| 250k | tickgrid | 59.9 | 31.9 | 4.7% | 9.6 | 10.0 | 2.26 | n/a | 1.0:1 |
| 250k | tv-batched | 82.7 | 20.9 | 1.9% | 9.8 | 10.0 | 2.63 | n/a | – |
| 250k | **tv-naive** | **COLLAPSED** | – | – | – | – | – | – | – |
| 500k | nullsink | 62.3 | 17.0 | 0.0% | – | – | 1.99 | – | – |
| 500k | tickgrid | 59.9 | 32.0 | 4.0% | 4.5 | 5.0 | 2.27 | n/a | 1.3:1 |
| 500k | tv-batched | 59.5 | 32.3 | 6.7% | 4.7 | 5.0 | 2.77 | n/a | – |

## 200 instruments — a concentrated book

| rate | impl | fps | stale p50 | cores | net cores | conflation | applied |
|---|---|---|---|---|---|---|---|
| 100k | nullsink | 61.1 | – | 0.11 | – | – | – |
| 100k | tickgrid | 59.9 | 0.9 | 0.45 | **0.34** | 8.8:1 | 90,711 |
| 100k | tv-batched | 57.1 | 1.0 | 2.06 | **1.95** | – | 796,096 |
| 500k | tickgrid | 59.7 | 0.2 | 2.29 | n/a | 24.8:1 | 194,172 |
| 500k | tv-batched | 59.7 | 0.1 | 2.80 | n/a | – | 4,796,837 |
| 1M | tickgrid | 59.9 | 0.1 | 2.22 | n/a | 41.7:1 | 307,874 |
| 1M | tv-batched | 59.9 | 0.2 | 2.82 | n/a | – | 12,807,653 |

## What the numbers say

**The naive approach is exactly as bad as the design document claims.** One `Platform.runLater` per
message dies at 50,000 msg/sec: 0.4 fps, every frame dropped, and values on screen nearly two
seconds old. At 100k it stops painting entirely and shows ten-second-old prices. At 250k it
collapses. That part of §1 needs no softening.

**A batched TableView is a genuinely strong competitor, and the design document does not admit
this.** At 5,000 instruments up to 250k msg/sec it matches TickGrid on CPU (0.23 against 0.23 net
cores at 50k), tracks the same staleness floor, and posts *better* frame times. §1's "there is no
good open-source answer" is too strong: a fixed-cell-size `TableView` with a batched update pump is
a good answer for a broad, slow-ticking universe, and it is perhaps forty lines of code.

**TickGrid wins decisively where conflation bites.** On a 200-instrument book at 100k msg/sec it
uses **0.34 net cores against 1.95 — 5.7x less** — because it applies 90,711 updates where
TableView applies 796,096. At 1M msg/sec the ratio reaches **41.7:1**: 307,874 applies against
12,807,653. Both hold 60 fps on this machine, so frame time hides the difference entirely; CPU is
where it shows, and CPU is what decides whether a desk can run twelve blotters on one workstation.

**Staleness tracks the theoretical floor throughout.** An instrument ticking every T ms shows a
value averaging T/2 old however good the grid is. TickGrid measured 250.9 against a 250.0 floor,
49.9 against 50.0, 24.6 against 25.0, 4.5 against 5.0. The pipeline contributes nothing measurable.
This is the number worth publishing, because conflation deliberately trades staleness for
throughput and a benchmark reporting only throughput hides the side of the trade the reader cares
about.

**Conflation does nothing at 5,000 instruments below 300k msg/sec**, measured at 1.0:1 across the
board — exactly as `max(1, rate / (keys x 60))` predicts. It is not a general-purpose speedup. It is
insurance against concentrated load, and it is worth having for the same reason a circuit breaker is
worth having.

## What these numbers do not show

**Above ~250k msg/sec the harness saturates.** The feed's own pacing spin pegs its two threads at
2.0 cores, so process CPU can no longer be attributed between feed and grid. Every "net cores"
figure at 250k and above is therefore marked n/a rather than guessed at. Clean CPU comparisons here
are valid only at 100k and below.

**Render throughput is never stressed.** A 1180x720 window shows ~45 rows, so every configuration
renders about 45 rows regardless of instrument count, and TableView's virtualization means ~99% of
updates at 5,000 instruments never touch a cell that exists. The comparison is mostly about update
plumbing, not painting.

Painting was measured separately, in a throwaway spike that is not part of this repository: a
60 x 15 grid of formatted text painted straight to a `Canvas` every frame, with no store, no ingress
and no virtualization behind it. On the same laptop that came to ~1.4 us of marginal cost per text
cell and a ceiling near 9,000 cells at 60 fps — which is why the renderer draws only the visible
range and does not otherwise work hard to avoid drawing. Those figures are quoted here as
background; nothing in this file depends on them, and they are not reproducible from this
repository.

**fps and dropped% are not strictly comparable between implementations.** TickGrid paints every
frame and sits vsync-locked at 59.9; TableView-batched sometimes reports 82 fps because JavaFX skips
rendering when no visible cell changed. Higher is not better there — it is doing less. The
comparable columns are staleness and CPU. TickGrid's own idle baseline (3.3% dropped, p99 31.9 ms)
is indistinguishable from its 500k msg/sec figures, which is the honest way to state that load costs
it nothing.

**One machine, one GPU, one run each.** Integrated Intel HD 620. No error bars.

---

# JMH microbenchmarks

`./gradlew :tickgrid-bench:jmh` — 2 forks, 3 warmup + 4 measurement iterations of 1s, `-prof gc`.
Same laptop. Raw output in [docs/jmh-results.txt](docs/jmh-results.txt).

Every error bar below is the 99.9% confidence interval JMH reports. Several are wide enough to
matter and are quoted rather than rounded away.

## Ingestion — §6's table

8 columns, a drain thread running at a 16.7 ms cadence with the design's 4 ms budget.

| unique keys | `submit` ops/s | allocation | `keyLookup` alone |
|---|---|---|---|
| 1,000 | 19,240,644 ± 946,460 | ≈0 B/op | 42,034,658 |
| 10,000 | 18,106,938 ± 3,233,869 | ≈0 B/op | 41,348,568 |
| 100,000 | 10,376,086 ± 536,834 | ≈0 B/op | 19,379,871 |

**The gate is met by two orders of magnitude.** §9 said six figures of msg/sec with near-zero
allocation would decide whether the premise held; the measured figure is 19.2 million a second at
a thousand keys and 10.4 million at a hundred thousand, at **zero bytes per operation** throughout.

The key count halves throughput between 10k and 100k, and the lookup-only row shows why: it halves
too, from 41.3M to 19.4M. The staging write is not what costs — the key index is, and it is
cache-bound.

Four threads over one shared key space reach 28.5M / 31.9M / 17.2M ops/s. That configuration
**violates the single-writer-per-key contract** and a torn row is possible under it; it is measured
because the contention it creates on the flags and the queue is real, and because knowing the cost
of the shape the contract forbids is how you know what the contract buys.

## The key index — and a methodological error worth more than the result

All variants raced inside **one** JMH run, 3 forks, 12 samples each:

| keys | threads | `KeyIndex` (two arrays) | entry-object layout | `ConcurrentHashMap` |
|---|---|---|---|---|
| 1,000 | 1 | **47.8M ± 5.1M** | 40.1M ± 2.3M | 31.6M ± 10.5M |
| 100,000 | 1 | 13.0M ± 2.1M | 10.9M ± 1.1M | 14.1M ± 1.3M |
| 1,000 | 4 | **98.7M ± 11.7M** | 93.3M ± 9.6M | 57.2M ± 8.3M |
| 100,000 | 4 | **33.4M ± 8.2M** | 25.3M ± 3.3M | 28.7M ± 10.0M |

All three allocate ≈0 B/op.

The shipped two-array layout is **1.5x faster than `ConcurrentHashMap` at a thousand keys, 1.7x
contended, and level with it at a hundred thousand.** Keeping it is justified.

### What this replaced, and why the first answer was wrong

An earlier version of this section said the opposite: that `ConcurrentHashMap` was 2.7x faster at a
hundred thousand keys, that the hand-rolled map should probably be deleted, and that the cause was
two parallel arrays costing two cache misses where one entry object would cost one. Acting on that
produced the entry-object layout above, which is **slower at every single point**.

Two separate mistakes, and the second is the one worth keeping in mind.

**The cache-line reasoning was backwards.** `slotPlusOne[i]` is a direct load from an `int[]` that
the prefetcher handles; `entry.slot` is a pointer chase to an object that must be dereferenced
first. The entry layout added an indirection rather than removing one. It is preserved as
`EntryKeyIndex` in the benchmark source so the comparison stays reproducible.

**The comparison was made across JMH runs, which on this machine is not a measurement.** The 2.7x
figure came from putting one run's `KeyIndex` number beside another run's `ConcurrentHashMap`
number. Between those two runs `ConcurrentHashMap` — unchanged code — scored 31.1M and then 77.8M
ops/sec at a thousand keys, and 42.9M and then 23.5M at a hundred thousand. **Run-to-run variance
was 2.5x; the effect being chased was 2.7x.** The conclusion was noise with a decimal point on it.

Only one claim from that section survives, and it is the one that started it: **the allocation
argument for hand-rolling the map was false.** `ConcurrentHashMap<K, Integer>` does not box on
lookup — boxing happens on insert, inserts are bounded by capacity, and a steady-state `get` returns
a reference that already exists. Measured, it allocates nothing. The map earns its place on
throughput and on dense slots, no rehash and an exact capacity bound. It never earned it on
allocation.

### Did making removal possible cost anything?

Retirement changed the shape of the index: insertion moved under a lock, and lookups gained a
tombstone check per probe. The lock is only on insertion, and these benchmarks measure lookups of
keys inserted during setup, so it should never fire during measurement. Worth checking rather than
asserting.

`CasKeyIndex` in the benchmark source is the class as it stood before retirement existed. Four
separate runs, 3 forks each, machine otherwise idle:

| keys | | run 1 | run 2 | run 3 | run 4 |
|---|---|---|---|---|---|
| 1,000 | before | 24.2M ± 8.6M | 50.2M ± 7.4M | 51.6M ± 0.9M | 50.2M ± 7.6M |
| 1,000 | after | 52.1M ± 6.8M | 55.8M ± 1.6M | 54.2M ± 4.8M | 50.6M ± 13.2M |
| 100,000 | before | 14.2M ± 2.9M | 14.7M ± 5.4M | 12.0M ± 1.0M | 17.6M ± 2.3M |
| 100,000 | after | 12.4M ± 2.3M | 13.2M ± 2.2M | 13.6M ± 4.8M | 11.5M ± 0.6M |

**No measurable difference, and the noise is the finding.** At a thousand keys the after/before
ratio per run is 2.15x, 1.11x, 1.05x, 1.01x — run 1's *before* number is an outlier at half the
throughput of the same code in the other three runs. At a hundred thousand the ratio is 0.87x,
0.90x, 1.13x, 0.65x: **the sign flips**, and unchanged code spans 12.0M to 17.6M across runs. A ten
percent effect cannot be resolved against a 1.5x spread.

Two intermediate readings were discarded getting here, both worth naming. A first pass measured a
2.3x *regression* and a second a 2.7x *improvement* — both were taken while a Gradle build and a
javadoc task were running on the same machine. Contaminated runs do not average out; they have to
be thrown away.

The second is subtler and revises the rule the section above lays down. "Race the variants inside
one run" is necessary but **not sufficient**: JMH forks each `@Benchmark` into its own JVM, so a
single run shares only the machine and the few minutes, not the JIT state or the heap layout. That
is enough to make one implementation's numbers move 2x between runs while its rival's stay put. The
usable rule is stronger — race the variants inside one run, run it several times, and believe an
effect only when it survives all of them with a consistent sign.

## Formatting and parsing

| operation | time | allocation |
|---|---|---|
| `FixedFormat.fixed` + the `String` the canvas forces | 26.2 ± 1.1 ns | 55.1 B/op |
| `FixedFormat.fixed` into the buffer only | 23.0 ± 9.9 ns | **≈0 B/op** |
| `String.format("%.2f")` | 542.3 ± 21.4 ns | 718.2 B/op |
| `FixedFormat.grouped` | 34.7 ± 2.2 ns | 55.9 B/op |
| `String.format("%,d")` | 1023.3 ± 75.0 ns | 1679.9 B/op |
| `FixedFormat.parseScaled` | 25.1 ± 3.3 ns | **≈0 B/op** |
| `Double.parseDouble` x 100 | 43.8 ± 2.8 ns | 71.1 B/op |

**`String.format` costs 20.7x the time and 13x the garbage**; the grouped form, which a volume
column needs, costs **29x and 30x**. The design's instinct to avoid it is confirmed with a margin
that is not close.

The two `fixed` rows isolate what the canvas spike could only infer. Formatting into a reused buffer
allocates **nothing**; the entire 55 bytes is the `String` that `GraphicsContext.fillText` requires,
and it costs 3 ns on top. That is the precise shape of the finding this review had to correct
earlier: the reused `char[]` does reach zero, and the residual is the API's fault, not the
formatter's.

Parsing goes the same way. `parseScaled` is **1.7x faster than routing through `double` and
allocates nothing**, so the exact-arithmetic choice is not a correctness-versus-speed trade — it
wins on both, and the `double` path is also wrong for values like `0.29`.

## Sorting

| rows | key spread | primitive merge | boxed comparator | speedup | boxed allocation |
|---|---|---|---|---|---|
| 1,000 | all distinct | 48.6 ± 1.0 us | 168.1 ± 4.8 us | 3.5x | 17.2 KB/op |
| 1,000 | 20 distinct | 38.5 ± 1.3 us | 151.8 ± 15.9 us | 3.9x | 17.2 KB/op |
| 50,000 | all distinct | 4,406.8 ± 197.9 us | 15,756.8 ± 3442.7 us | 3.6x | **1.03 MB/op** |
| 50,000 | 20 distinct | 3,107.0 ± 302.8 us | 8,407.6 ± 261.7 us | 2.7x | **1.03 MB/op** |

The primitive sort allocates 0.02-1.8 B/op — the scratch buffers are owned by the view model and
reused. The boxed version allocates **a megabyte per sort** at fifty thousand rows, four times a
second under the default policy.

For scale, `Arrays.sort` on the keys alone, carrying no slot permutation and offering no stability,
costs 18.7 us and 2,850 us for the two row counts. Carrying the permutation and being stable
therefore costs about 1.5x a bare primitive sort — which is the price of rows that do not jitter
between recomputes, and it is worth it.

## Caveats

- **`SortBenchmark` uses `Level.Invocation` setup** to restore the unsorted input, and JMH includes
  that cost in the measurement. It is an `arraycopy` of the row arrays: roughly 1-2% at these sizes,
  and it applies equally to every variant, so the ratios hold even though the absolute numbers are
  slightly pessimistic.
- **`keyLookup` calls `getOrCreate`, not `get`**, so it carries the create-path branch that
  `ConcurrentHashMap.get` does not. Mildly unfair to `KeyIndex`, and it now cuts in the direction of
  understating a result rather than explaining one away.
- **Never compare JMH scores across runs on this machine.** Run-to-run variance reached 2.5x on
  unchanged code. Anything being compared has to be a `@Benchmark` in the same class, raced by the
  same harness, in the same invocation. This is written down because ignoring it produced a
  confident, wrong, published conclusion earlier in this very file.
- **Two forks, not the three the build defaults to**, to keep the run inside twelve minutes. Wide
  intervals — `fixedFormatNoString` at ±9.9 ns on a 23 ns measurement, `boxedComparatorSort` at
  ±3,443 us — should be re-run with more forks before being quoted anywhere that matters.
- **One laptop.** Intel HD 620, Windows, JDK 21.0.11.
