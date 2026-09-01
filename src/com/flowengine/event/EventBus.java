package com.flowengine.event;

import com.flowengine.domain.*;
import com.flowengine.engine.FailureInjector;
import com.flowengine.metrics.MetricsManager;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/**
 * Custom event bus supporting publish/subscribe with per-event-type routing.
 * Thread-safe; supports multi-listener, rate limiting, and event stats.
 */
public class EventBus {

    private final ConcurrentHashMap<EventType, List<Subscriber>> subscribers = new ConcurrentHashMap<>();
    private final BlockingQueue<Event> eventQueue = new PriorityBlockingQueue<>(10000, (a, b) -> {
        int cmp = Integer.compare(b.getPriority(), a.getPriority()); // higher priority first
        return cmp != 0 ? cmp : Long.compare(a.getTimestamp(), b.getTimestamp());
    });
    private final ExecutorService dispatcher;
    private final MetricsManager metrics;
    private final FailureInjector failureInjector;
    private final AtomicLong totalPublished = new AtomicLong(0);
    private final AtomicLong totalConsumed = new AtomicLong(0);
    private final AtomicLong totalFiltered = new AtomicLong(0);
    private final AtomicLong totalDuplicates = new AtomicLong(0);
    private final List<Event> recentEvents = Collections.synchronizedList(new ArrayList<>());
    private final Set<String> seenEventKeys = ConcurrentHashMap.newKeySet();
    private volatile int maxRatePerSecond = 0; // 0 = no limit
    private final AtomicLong lastRateCheck = new AtomicLong(0);
    private final AtomicInteger rateCounter = new AtomicInteger(0);
    private volatile boolean running = true;
    private final ExecutorService rateEnforcer;
    private final ScheduledExecutorService statsCollector;

    public EventBus(MetricsManager metrics, FailureInjector failureInjector) {
        this.metrics = metrics;
        this.failureInjector = failureInjector;
        this.dispatcher = Executors.newFixedThreadPool(2, r -> {
            Thread t = new Thread(r, "event-bus-dispatcher");
            t.setDaemon(true);
            return t;
        });
        this.rateEnforcer = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "event-bus-rate-enforcer");
            t.setDaemon(true);
            return t;
        });
        this.statsCollector = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "event-bus-stats");
            t.setDaemon(true);
            return t;
        });
        startDispatcher();
    }

    public void subscribe(EventType type, Subscriber subscriber) {
        subscribers.computeIfAbsent(type, k -> new CopyOnWriteArrayList<>()).add(subscriber);
    }

    public void unsubscribe(EventType type, Subscriber subscriber) {
        List<Subscriber> list = subscribers.get(type);
        if (list != null) list.remove(subscriber);
    }

    public void publish(Event event) {
        // Deduplication check
        if (event.getIdempotencyKey() != null && !event.getIdempotencyKey().isBlank()) {
            if (!seenEventKeys.add(event.getIdempotencyKey())) {
                totalDuplicates.incrementAndGet();
                return;
            }
        }

        // Corrupt event injection - if enabled, drop the event
        if (failureInjector.shouldInjectCorruptEvent()) {
            metrics.recordEventDropped();
            return;
        }

        // Rate limiting
        if (maxRatePerSecond > 0 && !allowRate()) {
            metrics.recordEventRateLimited();
            rateEnforcer.submit(() -> eventQueue.offer(event));
            return;
        }

        if (!eventQueue.offer(event)) {
            metrics.recordEventDropped();
        } else {
            totalPublished.incrementAndGet();
        }
    }

    private boolean allowRate() {
        long now = System.currentTimeMillis();
        long last = lastRateCheck.get();
        if (now - last >= 1000) {
            if (lastRateCheck.compareAndSet(last, now)) {
                rateCounter.set(0);
            }
        }
        return rateCounter.incrementAndGet() <= maxRatePerSecond;
    }

    private void startDispatcher() {
        for (int i = 0; i < 2; i++) {
            dispatcher.submit(this::dispatchLoop);
        }
        statsCollector.scheduleAtFixedRate(this::collectStats, 1, 1, TimeUnit.SECONDS);
    }

    private void dispatchLoop() {
        while (running) {
            try {
                Event event = eventQueue.poll(200, TimeUnit.MILLISECONDS);
                if (event == null) continue;
                if (failureInjector.isPauseQueueEnabled()) {
                    // re-queue and pause
                    eventQueue.offer(event);
                    Thread.sleep(200);
                    continue;
                }
                dispatch(event);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                System.err.println("Event dispatch error: " + e.getMessage());
            }
        }
    }

    private void dispatch(Event event) {
        // Track recent events
        synchronized (recentEvents) {
            recentEvents.add(event);
            if (recentEvents.size() > 200) recentEvents.remove(0);
        }
        totalConsumed.incrementAndGet();
        metrics.recordEventProcessed(event.getType());
        List<Subscriber> subs = subscribers.get(event.getType());
        if (subs != null) {
            for (Subscriber s : subs) {
                try {
                    s.onEvent(event);
                } catch (Exception e) {
                    System.err.println("Subscriber error: " + e.getMessage());
                }
            }
        } else {
            totalFiltered.incrementAndGet();
        }
    }

    private void collectStats() {
        metrics.updateEventBusStats(totalPublished.get(), totalConsumed.get(),
            eventQueue.size(), totalDuplicates.get());
    }

    public int getQueueSize() { return eventQueue.size(); }
    public long getTotalPublished() { return totalPublished.get(); }
    public long getTotalConsumed() { return totalConsumed.get(); }
    public long getTotalDuplicates() { return totalDuplicates.get(); }
    public long getTotalFiltered() { return totalFiltered.get(); }
    public int getSubscriberCount(EventType type) {
        return subscribers.getOrDefault(type, Collections.emptyList()).size();
    }
    public List<Event> getRecentEvents(int limit) {
        synchronized (recentEvents) {
            int n = Math.min(limit, recentEvents.size());
            List<Event> r = new ArrayList<>(recentEvents.subList(Math.max(0, recentEvents.size() - n), recentEvents.size()));
            return r;
        }
    }

    public void setMaxRatePerSecond(int rate) { this.maxRatePerSecond = rate; }
    public int getMaxRatePerSecond() { return maxRatePerSecond; }

    public void shutdown() {
        running = false;
        dispatcher.shutdownNow();
        rateEnforcer.shutdown();
        statsCollector.shutdown();
    }

    public interface Subscriber {
        void onEvent(Event event);
    }
}
