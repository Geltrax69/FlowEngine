package com.flowengine.simulation;

import com.flowengine.domain.*;
import com.flowengine.engine.*;
import com.flowengine.event.EventBus;
import com.flowengine.metrics.MetricsManager;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/**
 * Generates realistic simulation data and drives the workflow engine
 * with configurable load levels.
 */
public class SimulationEngine {

    private final WorkflowEngine engine;
    private final EventBus eventBus;
    private final FailureInjector failureInjector;
    private final MetricsManager metrics;

    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2, r -> {
        Thread t = new Thread(r, "simulation-scheduler");
        t.setDaemon(true);
        return t;
    });

    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile int eventsPerSecond = 10;
    private volatile int maxEvents = 10000;
    private final AtomicLong eventsGenerated = new AtomicLong(0);
    private final AtomicLong eventsStopped = new AtomicLong(0);

    private final List<ScheduledFuture<?>> activeJobs = new ArrayList<>();

    // Generated entities
    private final List<Map<String, Object>> customers = new ArrayList<>();
    private final List<Map<String, Object>> orders = new ArrayList<>();
    private final Map<String, Workflow> workflows = new HashMap<>();

    public SimulationEngine(WorkflowEngine engine, EventBus eventBus, FailureInjector failureInjector, MetricsManager metrics) {
        this.engine = engine;
        this.eventBus = eventBus;
        this.failureInjector = failureInjector;
        this.metrics = metrics;
    }

    public void initializeData(int numCustomers, int numOrders) {
        customers.clear();
        for (int i = 0; i < numCustomers; i++) {
            Map<String, Object> c = new HashMap<>();
            c.put("customerId", "CUST-" + String.format("%05d", i));
            c.put("name", "Customer " + i);
            c.put("email", "customer" + i + "@example.com");
            c.put("age", 18 + (i % 60));
            c.put("customerType", i % 5 == 0 ? "PREMIUM" : "REGULAR");
            c.put("riskScore", (i * 7) % 100);
            c.put("creditLimit", 1000 + (i * 100) % 50000);
            customers.add(c);
        }
        orders.clear();
        for (int i = 0; i < numOrders; i++) {
            Map<String, Object> o = new HashMap<>();
            o.put("orderId", "ORD-" + String.format("%06d", i));
            o.put("customerId", customers.get(i % customers.size()).get("customerId"));
            o.put("amount", 100 + (i * 13) % 50000);
            o.put("itemCount", 1 + (i % 10));
            o.put("status", "CREATED");
            o.put("priority", i % 10 == 0 ? "HIGH" : "NORMAL");
            o.put("region", List.of("NORTH", "SOUTH", "EAST", "WEST").get(i % 4));
            orders.add(o);
        }
    }

    public void initializeWorkflows() {
        workflows.clear();
        workflows.putAll(createSampleWorkflows());
        for (Workflow wf : workflows.values()) {
            engine.registerWorkflow(wf);
        }
    }

    public void startSimulation() {
        if (running.compareAndSet(false, true)) {
            ScheduledFuture<?> job = scheduler.scheduleAtFixedRate(this::generateEvent,
                0, Math.max(10, 1000 / eventsPerSecond), TimeUnit.MILLISECONDS);
            activeJobs.add(job);
        }
    }

    public void stopSimulation() {
        running.set(false);
        for (ScheduledFuture<?> f : activeJobs) f.cancel(false);
        activeJobs.clear();
        eventsStopped.set(eventsGenerated.get());
    }

    private void generateEvent() {
        if (!running.get()) return;
        if (eventsGenerated.get() >= maxEvents) {
            stopSimulation();
            return;
        }

        long generated = eventsGenerated.incrementAndGet();

        // Rotate through event types
        EventType[] types = {
            EventType.CUSTOMER_REGISTERED,
            EventType.ORDER_CREATED,
            EventType.PAYMENT_COMPLETED,
            EventType.PAYMENT_FAILED,
            EventType.INVENTORY_RESERVED,
            EventType.CUSTOMER_CREATED
        };
        EventType type = types[(int) (generated % types.length)];

        Map<String, Object> payload = new HashMap<>();
        String idempotencyKey = null;

        switch (type) {
            case CUSTOMER_REGISTERED, CUSTOMER_CREATED -> {
                Map<String, Object> c = customers.get((int) (generated % customers.size()));
                payload.putAll(c);
                payload.put("timestamp", System.currentTimeMillis());
                idempotencyKey = "cust-" + c.get("customerId") + "-" + (generated % 10);
            }
            case ORDER_CREATED -> {
                Map<String, Object> o = orders.get((int) (generated % orders.size()));
                payload.putAll(o);
                payload.put("timestamp", System.currentTimeMillis());
                idempotencyKey = "order-" + o.get("orderId") + "-" + (generated % 10);
            }
            case PAYMENT_COMPLETED, PAYMENT_FAILED -> {
                Map<String, Object> o = orders.get((int) (generated % orders.size()));
                payload.put("orderId", o.get("orderId"));
                payload.put("customerId", o.get("customerId"));
                payload.put("amount", o.get("amount"));
                payload.put("status", type == EventType.PAYMENT_COMPLETED ? "SUCCESS" : "FAILED");
                payload.put("timestamp", System.currentTimeMillis());
                idempotencyKey = "pay-" + o.get("orderId") + "-" + (generated % 10);
            }
            case INVENTORY_RESERVED -> {
                payload.put("orderId", "ORD-" + String.format("%06d", generated % 500));
                payload.put("itemId", "ITEM-" + (generated % 100));
                payload.put("quantity", 1 + (generated % 5));
                payload.put("timestamp", System.currentTimeMillis());
                idempotencyKey = "inv-res-" + generated;
            }
            default -> {
                payload.put("eventId", generated);
                payload.put("timestamp", System.currentTimeMillis());
            }
        }

        // Duplicate event injection
        if (failureInjector.isDuplicateEventsEnabled() && Math.random() < 0.05) {
            eventBus.publish(new Event(UUID.randomUUID().toString(), type, "simulation",
                "default", payload, idempotencyKey));
            failureInjector.recordDuplicate();
        }

        Event event = new Event(UUID.randomUUID().toString(), type, "simulation",
            "default", payload, idempotencyKey);
        event.setPriority(5 - (int) (generated % 10));
        eventBus.publish(event);
        metrics.recordEventPublished();
    }

    public void setEventsPerSecond(int rate) { this.eventsPerSecond = Math.max(1, rate); }
    public void setMaxEvents(int max) { this.maxEvents = max; }
    public long getEventsGenerated() { return eventsGenerated.get(); }
    public boolean isRunning() { return running.get(); }

    // --- Sample Workflows ---

    public Map<String, Workflow> createSampleWorkflows() {
        Map<String, Workflow> result = new HashMap<>();

        // Workflow 1: Order Processing
        Workflow orderWf = new Workflow("order-processing", "Order Processing");
        orderWf.setDescription("Processes incoming orders through payment and fulfillment");

        WorkflowNode start = new WorkflowNode("o-start", NodeType.START, "Order Start", 50, 50);
        WorkflowNode eventNode = new WorkflowNode("o-event", NodeType.EVENT, "Order Created", 50, 140);
        eventNode.setConfig("eventType", "ORDER_CREATED");
        WorkflowNode condition = new WorkflowNode("o-cond", NodeType.CONDITION, "Check Amount", 50, 230);
        condition.setConfig("expression", "amount > 5000");
        WorkflowNode highValue = new WorkflowNode("o-high", NodeType.ACTION, "High Value Action", 200, 320);
        highValue.setConfig("actionType", "CHARGE_PAYMENT");
        WorkflowNode normalVal = new WorkflowNode("o-normal", NodeType.ACTION, "Normal Payment", 400, 320);
        normalVal.setConfig("actionType", "CHARGE_PAYMENT");
        WorkflowNode delay = new WorkflowNode("o-delay", NodeType.DELAY, "Processing Delay", 300, 410);
        delay.setConfig("delayMs", 2000);
        WorkflowNode notify = new WorkflowNode("o-notify", NodeType.ACTION, "Send Notification", 300, 500);
        notify.setConfig("actionType", "SEND_NOTIFICATION");
        WorkflowNode end = new WorkflowNode("o-end", NodeType.END, "Order Complete", 300, 590);

        // Build connections
        addAll(orderWf, start, eventNode, condition, highValue, normalVal, delay, notify, end);
        orderWf.addConnection(new Connection("oc1", start.getId(), eventNode.getId()));
        orderWf.addConnection(new Connection("oc2", eventNode.getId(), condition.getId()));
        orderWf.addConnection(new Connection("oc3a", condition.getId(), highValue.getId(), "true"));
        orderWf.addConnection(new Connection("oc3b", condition.getId(), normalVal.getId(), "false"));
        orderWf.addConnection(new Connection("oc4", highValue.getId(), delay.getId()));
        orderWf.addConnection(new Connection("oc5", normalVal.getId(), delay.getId()));
        orderWf.addConnection(new Connection("oc6", delay.getId(), notify.getId()));
        orderWf.addConnection(new Connection("oc7", notify.getId(), end.getId()));
        orderWf.setStartNodeId(start.getId());
        orderWf.setEndNodeId(end.getId());
        result.put(orderWf.getId(), orderWf);

        // Workflow 2: Customer Registration
        Workflow regWf = new Workflow("customer-registration", "Customer Registration");
        regWf.setDescription("Registers new customers and approves based on age");

        WorkflowNode rStart = new WorkflowNode("r-start", NodeType.START, "Start", 50, 50);
        WorkflowNode rEvent = new WorkflowNode("r-event", NodeType.EVENT, "Customer Registered", 50, 140);
        rEvent.setConfig("eventType", "CUSTOMER_REGISTERED");
        WorkflowNode rCond = new WorkflowNode("r-cond", NodeType.CONDITION, "Check Age >= 18", 50, 230);
        rCond.setConfig("expression", "age >= 18");
        WorkflowNode rApprove = new WorkflowNode("r-approve", NodeType.ACTION, "Approve Account", 200, 320);
        rApprove.setConfig("actionType", "APPROVE_ACCOUNT");
        WorkflowNode rReject = new WorkflowNode("r-reject", NodeType.ACTION, "Reject Application", 50, 320);
        rReject.setConfig("actionType", "REJECT_APPLICATION");
        WorkflowNode rNotify = new WorkflowNode("r-notify", NodeType.ACTION, "Send Welcome", 200, 410);
        rNotify.setConfig("actionType", "SEND_NOTIFICATION");
        WorkflowNode rEnd = new WorkflowNode("r-end", NodeType.END, "Complete", 200, 500);

        addAll(regWf, rStart, rEvent, rCond, rApprove, rReject, rNotify, rEnd);
        regWf.addConnection(new Connection("rc1", rStart.getId(), rEvent.getId()));
        regWf.addConnection(new Connection("rc2", rEvent.getId(), rCond.getId()));
        regWf.addConnection(new Connection("rc3a", rCond.getId(), rApprove.getId(), "true"));
        regWf.addConnection(new Connection("rc3b", rCond.getId(), rReject.getId(), "false"));
        regWf.addConnection(new Connection("rc4", rApprove.getId(), rNotify.getId()));
        regWf.addConnection(new Connection("rc5", rReject.getId(), rEnd.getId()));
        regWf.addConnection(new Connection("rc6", rNotify.getId(), rEnd.getId()));
        regWf.setStartNodeId(rStart.getId());
        regWf.setEndNodeId(rEnd.getId());
        result.put(regWf.getId(), regWf);

        // Workflow 3: Payment Flow
        Workflow payWf = new Workflow("payment-flow", "Payment Flow");
        payWf.setDescription("Handles payment processing with retry logic");

        WorkflowNode pStart = new WorkflowNode("p-start", NodeType.START, "Start", 50, 50);
        WorkflowNode pEvent = new WorkflowNode("p-event", NodeType.EVENT, "Payment Received", 50, 140);
        pEvent.setConfig("eventType", "PAYMENT_COMPLETED");
        WorkflowNode pCond = new WorkflowNode("p-cond", NodeType.CONDITION, "Payment Success?", 50, 230);
        pCond.setConfig("expression", "status == SUCCESS");
        WorkflowNode pCharge = new WorkflowNode("p-charge", NodeType.ACTION, "Charge Payment", 200, 320);
        pCharge.setConfig("actionType", "CHARGE_PAYMENT");
        pCharge.setConfig("maxRetries", 3);
        WorkflowNode pInvoice = new WorkflowNode("p-invoice", NodeType.ACTION, "Generate Invoice", 200, 410);
        pInvoice.setConfig("actionType", "GENERATE_INVOICE");
        WorkflowNode pEnd = new WorkflowNode("p-end", NodeType.END, "Complete", 200, 500);

        addAll(payWf, pStart, pEvent, pCond, pCharge, pInvoice, pEnd);
        payWf.addConnection(new Connection("pc1", pStart.getId(), pEvent.getId()));
        payWf.addConnection(new Connection("pc2", pEvent.getId(), pCond.getId()));
        payWf.addConnection(new Connection("pc3a", pCond.getId(), pCharge.getId(), "true"));
        payWf.addConnection(new Connection("pc3b", pCond.getId(), pEnd.getId(), "false"));
        payWf.addConnection(new Connection("pc4", pCharge.getId(), pInvoice.getId()));
        payWf.addConnection(new Connection("pc5", pInvoice.getId(), pEnd.getId()));
        payWf.setStartNodeId(pStart.getId());
        payWf.setEndNodeId(pEnd.getId());
        result.put(payWf.getId(), payWf);

        return result;
    }

    private void addAll(Workflow wf, WorkflowNode... nodes) {
        for (WorkflowNode n : nodes) wf.addNode(n);
    }

    public void shutdown() {
        stopSimulation();
        scheduler.shutdown();
    }
}
