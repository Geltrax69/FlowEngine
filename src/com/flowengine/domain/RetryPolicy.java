package com.flowengine.domain;

/**
 * Configures retry behavior for failed actions.
 */
public class RetryPolicy implements Cloneable {
    private int maxRetries = 3;
    private long initialDelayMs = 1000;
    private long maxDelayMs = 30000;
    private double backoffMultiplier = 2.0;
    private boolean exponential = true;
    private boolean exponentialMax = true;

    public RetryPolicy() {}

    public RetryPolicy(int maxRetries, long initialDelayMs) {
        this.maxRetries = maxRetries;
        this.initialDelayMs = initialDelayMs;
    }

    /** Compute delay for a given attempt number (1-based) */
    public long getDelayMs(int attemptNumber) {
        if (!exponential) return initialDelayMs;
        long delay = (long) (initialDelayMs * Math.pow(backoffMultiplier, attemptNumber - 1));
        return exponentialMax ? Math.min(delay, maxDelayMs) : delay;
    }

    public boolean canRetry(int currentAttempts) {
        return currentAttempts < maxRetries;
    }

    public int getMaxRetries() { return maxRetries; }
    public void setMaxRetries(int n) { this.maxRetries = n; }
    public long getInitialDelayMs() { return initialDelayMs; }
    public void setInitialDelayMs(long ms) { this.initialDelayMs = ms; }
    public long getMaxDelayMs() { return maxDelayMs; }
    public void setMaxDelayMs(long ms) { this.maxDelayMs = ms; }
    public double getBackoffMultiplier() { return backoffMultiplier; }
    public void setBackoffMultiplier(double m) { this.backoffMultiplier = m; }
    public boolean isExponential() { return exponential; }
    public void setExponential(boolean e) { this.exponential = e; }

    @Override
    public RetryPolicy clone() {
        try {
            return (RetryPolicy) super.clone();
        } catch (CloneNotSupportedException e) {
            return new RetryPolicy(maxRetries, initialDelayMs);
        }
    }

    @Override
    public String toString() {
        return String.format("RetryPolicy{max=%d, init=%dms, mult=%.1f, cap=%dms}",
            maxRetries, initialDelayMs, backoffMultiplier, maxDelayMs);
    }
}
