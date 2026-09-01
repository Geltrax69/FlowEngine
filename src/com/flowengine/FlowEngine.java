package com.flowengine;

import com.flowengine.domain.*;
import com.flowengine.engine.*;
import com.flowengine.event.EventBus;
import com.flowengine.metrics.MetricsManager;
import com.flowengine.persistence.PersistenceManager;
import com.flowengine.simulation.SimulationEngine;
import com.flowengine.concurrency.WorkerPool;
import com.flowengine.ui.DashboardFrame;

import javax.swing.*;

/**
 * FlowEngine — Event Driven Business Automation Platform
 *
 * A miniature workflow automation engine with visual builder, execution engine,
 * event bus, concurrency layer, failure injection, simulation, and real-time dashboard.
 *
 * Architecture:
 * - Domain layer: Workflow, WorkflowNode, Connection, Event, Task, RetryPolicy
 * - Engine layer: WorkflowEngine, ActionExecutor, IdempotencyManager, FailureInjector
 * - Event layer: EventBus (publish/subscribe with routing)
 * - Concurrency layer: WorkerPool, BlockingQueue task distribution
 * - Metrics layer: MetricsManager (counters, time-series, graphs)
 * - Persistence layer: PersistenceManager (workflows + execution logs to disk)
 * - Simulation layer: SimulationEngine (data generation, event streaming)
 * - UI layer: DashboardFrame, WorkflowCanvas, MetricsGraphPanel
 *
 * Key algorithms:
 * - Graph traversal: Depth-first with topological cycle detection
 * - Scheduling: Priority queue with FIFO for equal priority
 * - Worker pool: Fixed thread pool with dynamic task distribution
 * - Retry: Exponential backoff with configurable cap
 * - Idempotency: Sliding-window in-memory cache with TTL
 * - Event bus: Multi-listener pub/sub with per-type routing
 *
 * @author FlowEngine Platform
 */
public class FlowEngine {

    public static void main(String[] args) {
        // Set look and feel
        try {
            UIManager.setLookAndFeel(UIManager.getCrossPlatformLookAndFeelClassName());
        } catch (Exception e) {
            System.err.println("Look and feel not set: " + e.getMessage());
        }

        // --- Wire up all components ---
        System.out.println("FlowEngine starting...");
        System.out.println("=".repeat(60));

        // Core infrastructure
        MetricsManager metrics = new MetricsManager();
        FailureInjector failureInjector = new FailureInjector();
        IdempotencyManager idempotencyManager = new IdempotencyManager();

        // Event bus (before engine so engine can subscribe)
        EventBus eventBus = new EventBus(metrics, failureInjector);

        // Workflow engine (10 workers default)
        int numWorkers = 10;
        if (args.length > 0) {
            try { numWorkers = Integer.parseInt(args[0]); } catch (Exception ignored) {}
        }
        WorkflowEngine engine = new WorkflowEngine(eventBus, metrics, failureInjector, numWorkers);

        // Persistence
        String dataDir = System.getProperty("user.home") + "/.flowengine/data";
        PersistenceManager persistence = new PersistenceManager(dataDir);

        // Load persisted workflows, or create sample ones
        var loaded = persistence.loadWorkflows();
        if (!loaded.isEmpty()) {
            System.out.println("Loaded " + loaded.size() + " persisted workflow(s)");
            for (Workflow wf : loaded) engine.registerWorkflow(wf);
        }

        // Simulation engine
        SimulationEngine simulation = new SimulationEngine(engine, eventBus, failureInjector, metrics);
        simulation.initializeData(100, 500);
        if (loaded.isEmpty()) {
            System.out.println("Initializing sample workflows...");
            simulation.initializeWorkflows();
        }
        System.out.println("Registered workflows: " + engine.getAllWorkflows().size());

        // Worker pool reference for dashboard
        WorkerPool workerPool = new WorkerPool(numWorkers, engine, metrics, failureInjector);

        // --- Print architecture summary ---
        System.out.println();
        System.out.println("FlowEngine Architecture Summary:");
        System.out.println("-".repeat(40));
        System.out.println("  Domain Model: Workflow, WorkflowNode, Connection, Event, Task");
        System.out.println("  Execution Engine: Graph traversal, condition evaluation, action execution");
        System.out.println("  Event Bus: Multi-listener pub/sub with routing + deduplication");
        System.out.println("  Worker Pool: " + numWorkers + " workers, priority queue, dynamic task distribution");
        System.out.println("  Idempotency: TTL-based in-memory cache");
        System.out.println("  Retry Strategy: Exponential backoff with jitter cap");
        System.out.println("  Persistence: Text-based workflow + execution logs to ~/.flowengine/");
        System.out.println("  Simulation: 100 customers, 500 orders, configurable event rate");
        System.out.println("-".repeat(40));
        System.out.println("Launching UI...");
        System.out.println("=".repeat(60));

        // Launch dashboard on EDT
        SwingUtilities.invokeLater(() -> {
            DashboardFrame frame = new DashboardFrame(
                metrics, failureInjector, eventBus, engine, simulation,
                persistence, workerPool
            );
            frame.setVisible(true);
        });

        // Graceful shutdown hook
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("\nFlowEngine shutting down...");
            simulation.shutdown();
            engine.shutdown();
            eventBus.shutdown();
            metrics.shutdown();
            idempotencyManager.shutdown();
            workerPool.shutdown();
            System.out.println("FlowEngine stopped.");
        }));
    }
}
