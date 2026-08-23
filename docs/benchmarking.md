# Why JMH does not run in CI

The microbenchmarks are not part of the CI matrix, and that is deliberate.

A shared runner gives you a noisy neighbour, an unknown CPU, and no control over frequency scaling.
JMH numbers from one are not comparable between runs, which makes a regression threshold either so
loose it catches nothing or so tight it fails on weather. Publishing them would be worse than not
measuring: a number with a source nobody trusts still gets quoted.

What CI does instead is `bench-smoke`, which runs the frame-time harness briefly at one rate. That
catches a harness that no longer starts, which is the failure a CI job can actually detect.

Run the microbenchmarks on a quiet machine you control:

    ./gradlew :tickgrid-bench:jmh

Quick check while iterating (do not quote these):

    ./gradlew :tickgrid-bench:jmh -Pjmh.fork=1 -Pjmh.warmup=2 -Pjmh.iterations=3 \
        -Pjmh.include=FormatBenchmark
