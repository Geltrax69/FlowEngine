# FlowEngine — Event Driven Business Automation Platform

A miniature production-grade workflow automation engine with a visual builder, execution engine, event bus, worker pool, failure injection, simulation, and real-time dashboard. Built entirely with **pure Java + Swing/AWT** — no external libraries, no databases, no web technologies.

---

## Quick Start

```bash
# Compile
javac -d out -sourcepath src src/com/flowengine/FlowEngine.java

# Run (10 worker threads)
java -cp out com.flowengine.FlowEngine

# Run with custom worker count
java -cp out com.flowengine.FlowEngine 20
```

> **Requirements:** Java 8+ (uses only java.desktop, java.base, java.util.concurrent)

---

## Screenshots

The dashboard shows live metrics including:
- Active/Completed/Failed workflow counts
- Event throughput (events/sec)
- Worker pool status
- Queue size and retry counts
- Real-time graphs for events/sec, active workflows, queue size

---

## Architecture

```
┌─────────────────────────────────────────────────────────┐
│                    DashboardFrame (Swing)                │
│  ┌──────────────┐ ┌──────────────┐ ┌────────────────┐ │
│  │ WorkflowCanvas│ │   Metrics    │ │ FailureInject  │ │
│  │  (Builder)   │ │  (Live KPIs) │ │   Controls     │ │
│  └──────┬───────┘ └──────┬───────┘ └───────┬────────┘ │
│         │                │                  │           │
│         └────────────────┼──────────────────┘           │
│                          │                               │
│              ┌───────────▼────────────┐                │
│              │     WorkflowEngine     │                │
│              │   (Graph Execution)    │                │
│              └───────────┬────────────┘                │
│         ┌───────────────┼────────────────┐             │
│         │               │                │             │
│  ┌──────▼──────┐ ┌─────▼─────┐ ┌──────▼──────┐    │
│  │ ActionExec   │ │Expression │ │  RetryPolicy │    │
│  └─────────────┘ └───────────┘ └─────────────┘    │
│         │                                                   │
│  ┌──────▼──────────────────────────────────────┐         │
│  │             EventBus (Pub/Sub)               │         │
│  │  Subscribers → Priority Queue → Dispatchers  │         │
│  └────────────────────┬─────────────────────────┘         │
│                       │                                   │
│              ┌────────▼────────┐                        │
│              │   WorkerPool   │                        │
│              │ (10 threads)   │                        │
│              └────────────────┘                        │
│                                                              │
│  ┌──────────────┐ ┌─────────────────┐                     │
│  │MetricsManager│ │ PersistenceMgr  │                     │
│  │ (Counters)   │ │  (File logs)   │                     │
│  └──────────────┘ └─────────────────┘                     │
└─────────────────────────────────────────────────────────┘
```

### Package Breakdown

| Package | Classes | Responsibility |
|---|---|---|
| `domain` | Workflow, WorkflowNode, Connection, Event, Task, RetryPolicy | Domain model |
| `engine` | WorkflowEngine, ActionExecutor, IdempotencyManager, FailureInjector | Core execution logic |
| `event` | EventBus | Publish/subscribe event routing |
| `concurrency` | WorkerPool | Thread pool + task distribution |
| `metrics` | MetricsManager | Counters, time-series, statistics |
| `simulation` | SimulationEngine | Load generation + sample data |
| `persistence` | PersistenceManager | Workflow + execution logs to disk |
| `expression` | ExpressionEvaluator | Custom expression parser |
| `ui` | DashboardFrame, WorkflowCanvas, MetricsGraphPanel | Swing UI |

---

## Key Algorithms

### Graph Traversal
The workflow graph is traversed depth-first. Before execution, nodes are ordered topologically to detect cycles. Each node type has its own execution strategy:

```
Event → Condition → Decision Branch → Action(s) → Delay → Action → End
```

### Scheduling Algorithm
Tasks use a **PriorityBlockingQueue** with comparator:
- Higher priority tasks run first (priority 1 = highest)
- Equal priority: FIFO by creation timestamp
- Dynamic priority adjustment based on workflow state

### Worker Pool
- Fixed-size thread pool (default 10 workers)
- Each worker polls the shared task queue
- Dead workers are automatically respawned
- Network delay simulation injects configurable latency per task
- Random worker crash simulation for resilience testing

### Event Bus
- Multi-listener pub/sub: many subscribers per event type
- Priority queue for event ordering
- Rate limiting with token bucket (configurable events/sec cap)
- Deduplication via idempotency key

### Retry Strategy
Exponential backoff with jitter cap:
```
attempt 1: 1s,  attempt 2: 2s,  attempt 3: 4s,  attempt 4: 8s ...
cap: 30 seconds (configurable)
```
Dead-letter state after `maxRetries` exceeded.

### Idempotency
Sliding-window TTL cache. Each event has an idempotency key (e.g., `cust-CUST-001-0`). The cache tracks processed keys with a 5-minute TTL. Duplicate events are dropped silently. TTL-based cleanup prevents memory growth.

### Failure Recovery
- **Worker crash**: Worker is marked dead, respawned automatically
- **Task failure**: Retry with exponential backoff
- **Node failure**: Workflow enters DEAD_LETTER state
- **Duplicate event**: Silently dropped by idempotency check
- **Network delay**: Configurable per-task latency injection
- **Workflow cancellation**: State set to CANCELLED, no further tasks queued

---

## Race Condition Prevention

| Resource | Mechanism |
|---|---|
| Execution map | `ReadWriteLock` — read-heavy, write-exclusive |
| Idempotency cache | `ConcurrentHashMap` with `putIfAbsent` |
| Task queue | `PriorityBlockingQueue` (thread-safe by design) |
| Event queue | `BlockingQueue` (thread-safe by design) |
| Metrics counters | `AtomicInteger` / `AtomicLong` |
| Worker status | `volatile` + `AtomicReference` |
| Workflow graph | Locked during modifications |

---

## Node Types

| Node | Description | Config |
|---|---|---|
| **START** | Entry point | — |
| **EVENT** | Triggers workflow on event type | `eventType` |
| **CONDITION** | Boolean expression branch | `expression` |
| **ACTION** | Simulated business action | `actionType`, `maxRetries` |
| **DELAY** | Pause execution | `delayMs` |
| **PARALLEL** | Fan out to multiple branches | — |
| **APPROVAL** | Manual approval gate | `approver`, `timeoutMs` |
| **END** | Terminal node | — |

### Condition Examples
```
age >= 18
amount > 5000 AND riskScore < 30
customerType == PREMIUM OR status == SUCCESS
NOT (amount < 100)
inventory > 0 AND (region == NORTH OR region == SOUTH)
```

---

## Failure Injection Buttons

| Button | Effect |
|---|---|
| **Kill Worker** | Randomly terminates a worker thread (auto-respawns) |
| **Flood Events** | Injects burst of events into the event bus |
| **Duplicate Events** | Re-publishes recent events with same idempotency key |
| **Slow Network** | Adds 1–5s delay per task (configurable) |
| **Random Failures** | 5% random failure rate on all actions |
| **Corrupt Events** | Silently drops events |
| **Pause Queue** | Halts task processing (resume to continue) |

---

## Action Types

| Action | Avg Duration | Failure Rate |
|---|---|---|
| SendNotification | 200ms | 5% |
| CreateOrder | 500ms | 3% |
| ReserveInventory | 300ms | 2% |
| ChargePayment | 800ms | 8% |
| GenerateInvoice | 400ms | 1% |
| AssignDriver | 600ms | 4% |
| UpdateCustomer | 150ms | 2% |
| ApproveAccount | 300ms | 1% |
| RejectApplication | 200ms | 1% |
| ShipOrder | 500ms | 3% |
| RetryPayment | 400ms | 10% |
| SendEmail | 200ms | 2% |
| LogAudit | 50ms | 0.1% |
| Escalate | 300ms | 1% |

---

## Persistence

Workflows and execution logs are saved to `~/.flowengine/`:
```
~/.flowengine/data/workflows/    → workflow definitions (.wf)
~/.flowengine/data/executions/   → execution logs (.log)
```

Files are plain-text, human-readable, and survive application restarts.

---

## Time Complexity

| Operation | Complexity |
|---|---|
| Expression evaluation | O(n) where n = expression length |
| Graph traversal | O(V + E) — topological sort |
| Task scheduling | O(log n) per task (priority queue) |
| Idempotency check | O(1) average (ConcurrentHashMap) |
| Event routing | O(k) where k = subscribers per type |
| Metrics aggregation | O(1) per update, O(n) for series |

---

## Scalability Limitations

| Limitation | Reason | Production Fix |
|---|---|---|
| Single JVM | No distributed coordination | Redis + Kafka for multi-node |
| In-memory execution state | Lost on crash | PostgreSQL + event sourcing |
| Single event bus thread | Bounded throughput | Kafka/RabbitMQ |
| No persistence (executions) | Execution state volatile | WAL + checkpointing |
| Fixed worker count | Cannot auto-scale | Kubernetes HPA |
| No clustering | Single point of failure | Raft consensus |

---

## Converting to Distributed Backend

### Step 1: Replace WorkerPool with Kafka Consumer Groups
Each worker becomes a Kafka consumer in a shared group. Tasks are published to Kafka topics by workflow ID for ordering guarantees.

### Step 2: Replace In-Memory EventBus with Message Broker
Use RabbitMQ or Kafka topics per event type. Subscribers are message listeners.

### Step 3: Replace Execution Map with Database
Store `WorkflowExecution` in PostgreSQL with optimistic locking. Use Redis for hot data (current state).

### Step 4: Add Service Layer
```
API Gateway → WorkflowService → EngineWorker → DB
                    ↓
              EventPublisher → Kafka
```

### Step 5: Add Observability
- **Metrics**: Micrometer → Prometheus → Grafana
- **Tracing**: OpenTelemetry → Jaeger
- **Logging**: Logback → ELK Stack

### Step 6: Add Circuit Breakers
Wrap external service calls with Resilience4j. The `FailureInjector` simulates the same failure modes.

### Tech Stack for Production Conversion
```
Java 17 + Spring Boot
Apache Kafka (event sourcing)
PostgreSQL (workflow + execution state)
Redis (caching, idempotency, distributed locks)
Elasticsearch (execution logs)
Prometheus + Grafana (metrics)
Jaeger (distributed tracing)
Docker + Kubernetes (orchestration)
```

---

## File Structure

```
FlowEngine/
├── README.md
└── src/com/flowengine/
    ├── FlowEngine.java                    # Main entry point
    ├── domain/
    │   ├── NodeType.java                 # Node type enum
    │   ├── WorkflowNode.java             # Node model
    │   ├── Connection.java               # Edge model
    │   ├── Workflow.java                 # Graph definition
    │   ├── WorkflowExecution.java        # Execution state
    │   ├── NodeExecutionRecord.java     # Execution history
    │   ├── ExecutionState.java          # State enum
    │   ├── Event.java                   # Event model
    │   ├── EventType.java               # Event type enum
    │   ├── ActionType.java              # Action type enum
    │   ├── Task.java                    # Task unit
    │   └── RetryPolicy.java             # Retry configuration
    ├── engine/
    │   ├── WorkflowEngine.java           # Core execution engine
    │   ├── ActionExecutor.java          # Simulated action execution
    │   ├── IdempotencyManager.java      # Idempotency cache
    │   └── FailureInjector.java         # Failure simulation
    ├── event/
    │   └── EventBus.java                # Pub/sub event routing
    ├── concurrency/
    │   └── WorkerPool.java              # Thread pool + workers
    ├── metrics/
    │   └── MetricsManager.java          # Counters + time-series
    ├── simulation/
    │   └── SimulationEngine.java         # Load generator
    ├── persistence/
    │   └── PersistenceManager.java      # File-based persistence
    ├── expression/
    │   └── ExpressionEvaluator.java     # Condition parser
    └── ui/
        ├── DashboardFrame.java           # Main dashboard window
        ├── WorkflowCanvas.java           # Visual workflow builder
        └── MetricsGraphPanel.java       # Real-time graphs
```

---

## Usage

1. **Launch**: `java -cp out com.flowengine.FlowEngine`
2. **Canvas tab**: Build workflows by clicking to add nodes, right-click-drag to connect them
3. **Simulation tab**: Configure event rate and click "Start Simulation"
4. **Dashboard tab**: Watch live metrics update
5. **Failure tab**: Toggle failure injection modes
6. **Workers tab**: Monitor individual worker threads
7. **Executions tab**: View running and completed workflows
8. **Events tab**: See live event stream
