package com.gatekeeper.resilience.circuitbreaker;

/**
 * Thread-safe sliding window ring buffer for recording and evaluating call outcomes.
 */
public class SlidingWindow {

    public enum Outcome {
        SUCCESS,
        SLOW_SUCCESS,
        FAILURE,
        SLOW_FAILURE
    }

    private final int capacity;
    private final Outcome[] ring;
    private int head = 0;
    private int size = 0;
    private final Object lock = new Object();

    public SlidingWindow(int capacity) {
        this.capacity = Math.max(1, capacity);
        this.ring = new Outcome[this.capacity];
    }

    public void record(Outcome outcome) {
        synchronized (lock) {
            ring[head] = outcome;
            head = (head + 1) % capacity;
            if (size < capacity) {
                size++;
            }
        }
    }

    public Snapshot getSnapshot() {
        synchronized (lock) {
            long successes = 0;
            long failures = 0;
            long slow = 0;
            for (int i = 0; i < size; i++) {
                Outcome o = ring[i];
                if (o == Outcome.SUCCESS) {
                    successes++;
                } else if (o == Outcome.SLOW_SUCCESS) {
                    successes++;
                    slow++;
                } else if (o == Outcome.FAILURE) {
                    failures++;
                } else if (o == Outcome.SLOW_FAILURE) {
                    failures++;
                    slow++;
                }
            }
            float failureRate = size > 0 ? (failures * 100.0f) / size : 0.0f;
            float slowRate = size > 0 ? (slow * 100.0f) / size : 0.0f;
            return new Snapshot(size, successes, failures, slow, failureRate, slowRate);
        }
    }

    public void clear() {
        synchronized (lock) {
            head = 0;
            size = 0;
            for (int i = 0; i < capacity; i++) {
                ring[i] = null;
            }
        }
    }

    public record Snapshot(
        int totalCalls,
        long successfulCalls,
        long failedCalls,
        long slowCalls,
        float failureRate,
        float slowCallRate
    ) {}
}
