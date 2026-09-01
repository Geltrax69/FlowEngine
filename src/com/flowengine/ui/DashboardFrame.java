package com.flowengine.ui;

import com.flowengine.domain.*;
import com.flowengine.engine.*;
import com.flowengine.event.EventBus;
import com.flowengine.metrics.MetricsManager;
import com.flowengine.persistence.PersistenceManager;
import com.flowengine.simulation.SimulationEngine;
import com.flowengine.concurrency.WorkerPool;

import javax.swing.*;
import javax.swing.border.*;
import javax.swing.table.*;
import java.awt.*;
import java.awt.event.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.List;
import java.util.Timer;

/**
 * Main dashboard window — the control center for the FlowEngine platform.
 * Hosts canvas, simulation controls, metrics, failure injection, and log views.
 */
public class DashboardFrame extends JFrame {

    // --- Core systems ---
    private final MetricsManager metrics;
    private final FailureInjector failureInjector;
    private final EventBus eventBus;
    private final WorkflowEngine engine;
    private final SimulationEngine simulation;
    private final PersistenceManager persistence;
    private final WorkerPool workerPool;
    private Workflow currentWorkflow;

    // --- UI Components ---
    private JTabbedPane mainTabs;
    private WorkflowCanvas canvas;
    private JList<String> workflowList;
    private DefaultListModel<String> workflowListModel;
    private JTable executionTable;
    private DefaultTableModel executionTableModel;
    private JTextArea eventLogArea;
    private JTable eventTable;
    private DefaultTableModel eventTableModel;
    private JTable workerTable;
    private DefaultTableModel workerTableModel;
    private JLabel activeLabel, completedLabel, failedLabel, retryingLabel, waitingLabel, queuedLabel;
    private JLabel workersLabel, eventRateLabel, avgExecLabel, failureRateLabel, queueSizeLabel;
    private JLabel dupLabel, successLabel, failLabel, execThroughputLabel;
    private MetricsGraphPanel graphPanel;
    private JLabel simStatusLabel;
    private JTextField eventsPerSecField;
    private JTextField numWorkersField;
    private JTextField maxRetriesField;
    private JTextField maxEventsField;
    private JTextField rateLimitField;
    private JButton floodButton;
    private Timer refreshTimer;

    public DashboardFrame(MetricsManager metrics, FailureInjector failureInjector, EventBus eventBus,
                          WorkflowEngine engine, SimulationEngine simulation,
                          PersistenceManager persistence, WorkerPool workerPool) {
        this.metrics = metrics;
        this.failureInjector = failureInjector;
        this.eventBus = eventBus;
        this.engine = engine;
        this.simulation = simulation;
        this.persistence = persistence;
        this.workerPool = workerPool;

        setTitle("FlowEngine — Event Driven Business Automation Platform");
        setSize(1600, 1000);
        setLocationRelativeTo(null);
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        getContentPane().setBackground(new Color(30, 34, 42));
        setLayout(new BorderLayout());

        initTopBar();
        initMainTabs();

        // Auto-refresh every 500ms
        refreshTimer = new Timer();
        refreshTimer.scheduleAtFixedRate(new java.util.TimerTask() {
            @Override
            public void run() {
                SwingUtilities.invokeLater(() -> refreshAll());
            }
        }, 500, 500);

        addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent e) {
                shutdown();
            }
        });
    }

    private void initTopBar() {
        JPanel topBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 8));
        topBar.setBackground(new Color(20, 24, 30));
        topBar.setBorder(new EmptyBorder(5, 10, 5, 10));

        JLabel title = new JLabel("⚡ FlowEngine");
        title.setFont(new Font("SansSerif", Font.BOLD, 18));
        title.setForeground(new Color(0, 180, 255));
        topBar.add(title);

        JLabel subtitle = new JLabel("  Event Driven Business Automation Platform");
        subtitle.setFont(new Font("SansSerif", Font.ITALIC, 12));
        subtitle.setForeground(new Color(150, 160, 170));
        topBar.add(subtitle);

        topBar.add(Box.createHorizontalStrut(20));
        simStatusLabel = new JLabel("● Simulation: STOPPED");
        simStatusLabel.setForeground(new Color(220, 50, 50));
        simStatusLabel.setFont(new Font("Monospaced", Font.BOLD, 12));
        topBar.add(simStatusLabel);

        topBar.add(Box.createHorizontalStrut(20));
        JButton startSim = createStyledButton("▶ Start Simulation", new Color(46, 204, 113));
        startSim.addActionListener(e -> {
            simulation.startSimulation();
            simStatusLabel.setText("● Simulation: RUNNING");
            simStatusLabel.setForeground(new Color(46, 204, 113));
        });
        topBar.add(startSim);

        JButton stopSim = createStyledButton("■ Stop Simulation", new Color(231, 76, 60));
        stopSim.addActionListener(e -> {
            simulation.stopSimulation();
            simStatusLabel.setText("● Simulation: STOPPED");
            simStatusLabel.setForeground(new Color(220, 50, 50));
        });
        topBar.add(stopSim);

        topBar.add(Box.createHorizontalStrut(20));
        JButton resetMetrics = createStyledButton("Reset Metrics", new Color(241, 196, 15));
        resetMetrics.addActionListener(e -> {
            persistence.purgeOldLogs(0);
            JOptionPane.showMessageDialog(this, "Old logs cleared");
        });
        topBar.add(resetMetrics);

        add(topBar, BorderLayout.NORTH);
    }

    private void initMainTabs() {
        mainTabs = new JTabbedPane();
        mainTabs.setBackground(new Color(35, 40, 50));
        mainTabs.setForeground(Color.WHITE);

        mainTabs.addTab("🎨 Canvas", createCanvasTab());
        mainTabs.addTab("📊 Dashboard", createMetricsTab());
        mainTabs.addTab("📝 Executions", createExecutionsTab());
        mainTabs.addTab("📨 Events", createEventsTab());
        mainTabs.addTab("👷 Workers", createWorkersTab());
        mainTabs.addTab("⚙️ Failure Injection", createFailureTab());
        mainTabs.addTab("🔧 Simulation", createSimulationTab());

        add(mainTabs, BorderLayout.CENTER);
    }

    private JPanel createCanvasTab() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBackground(new Color(30, 34, 42));

        // Left: workflow list
        JPanel leftPanel = new JPanel(new BorderLayout());
        leftPanel.setBackground(new Color(30, 34, 42));
        leftPanel.setPreferredSize(new Dimension(220, 0));
        leftPanel.setBorder(new TitledBorder(new LineBorder(new Color(60, 70, 80)), "Workflows",
            0, 0, new Font("SansSerif", Font.BOLD, 12), Color.WHITE));

        workflowListModel = new DefaultListModel<>();
        refreshWorkflowList();
        workflowList = new JList<>(workflowListModel);
        workflowList.setBackground(new Color(38, 42, 52));
        workflowList.setForeground(Color.WHITE);
        workflowList.setSelectionBackground(new Color(0, 100, 180));
        workflowList.setCellRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                boolean isSelected, boolean cellHasFocus) {
                JLabel lbl = (JLabel) super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                lbl.setBorder(new EmptyBorder(6, 10, 6, 10));
                if (!isSelected) lbl.setBackground(new Color(38, 42, 52));
                lbl.setForeground(Color.WHITE);
                return lbl;
            }
        });
        workflowList.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                int idx = workflowList.getSelectedIndex();
                if (idx >= 0 && idx < engine.getAllWorkflows().size()) {
                    currentWorkflow = (Workflow) engine.getAllWorkflows().toArray()[idx];
                    canvas.setWorkflow(currentWorkflow);
                }
            }
        });

        JScrollPane listScroll = new JScrollPane(workflowList);
        listScroll.setBorder(null);
        leftPanel.add(listScroll, BorderLayout.CENTER);

        // Right: canvas
        JPanel rightPanel = new JPanel(new BorderLayout());
        rightPanel.setBackground(new Color(30, 34, 42));

        // Node type toolbar
        JPanel nodeTypeBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 5));
        nodeTypeBar.setBackground(new Color(38, 42, 52));
        nodeTypeBar.setBorder(new TitledBorder(new LineBorder(new Color(60, 70, 80)), "Add Node",
            0, 0, new Font("SansSerif", Font.BOLD, 11), Color.WHITE));
        ButtonGroup group = new ButtonGroup();
        for (NodeType type : NodeType.values()) {
            JToggleButton btn = new JToggleButton(type.label);
            btn.setBackground(new Color(50, 55, 65));
            btn.setForeground(Color.WHITE);
            btn.setFocusPainted(false);
            btn.setSelected(type == NodeType.ACTION);
            btn.addActionListener(e -> {
                if (canvas != null) canvas.setSelectedNodeType(type);
            });
            group.add(btn);
            nodeTypeBar.add(btn);
        }
        rightPanel.add(nodeTypeBar, BorderLayout.NORTH);

        canvas = new WorkflowCanvas(wf -> {
            if (currentWorkflow != null && wf != null) {
                persistence.saveWorkflow(currentWorkflow);
            }
        });
        JScrollPane canvasScroll = new JScrollPane(canvas);
        canvasScroll.setBorder(null);
        rightPanel.add(canvasScroll, BorderLayout.CENTER);

        // Bottom controls
        JPanel bottomBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 5));
        bottomBar.setBackground(new Color(38, 42, 52));
        JButton deleteBtn = createStyledButton("🗑 Delete Selected", new Color(231, 76, 60));
        deleteBtn.addActionListener(e -> {
            if (canvas != null) canvas.deleteSelected();
        });
        JButton runBtn = createStyledButton("▶ Run Workflow", new Color(46, 204, 113));
        runBtn.addActionListener(e -> {
            if (currentWorkflow != null) {
                engine.startWorkflow(currentWorkflow.getId(), null);
            }
        });
        JButton saveBtn = createStyledButton("💾 Save", new Color(52, 152, 219));
        saveBtn.addActionListener(e -> {
            if (currentWorkflow != null) {
                persistence.saveWorkflow(currentWorkflow);
                JOptionPane.showMessageDialog(this, "Workflow saved: " + currentWorkflow.getName());
            }
        });
        JButton newBtn = createStyledButton("➕ New Workflow", new Color(155, 89, 182));
        newBtn.addActionListener(e -> createNewWorkflow());
        bottomBar.add(deleteBtn);
        bottomBar.add(runBtn);
        bottomBar.add(saveBtn);
        bottomBar.add(newBtn);
        rightPanel.add(bottomBar, BorderLayout.SOUTH);

        panel.add(leftPanel, BorderLayout.WEST);
        panel.add(rightPanel, BorderLayout.CENTER);
        return panel;
    }

    private JPanel createMetricsTab() {
        JPanel panel = new JPanel(new BorderLayout(10, 10));
        panel.setBackground(new Color(30, 34, 42));
        panel.setBorder(new EmptyBorder(10, 10, 10, 10));

        // Top: stat cards
        JPanel cards = new JPanel(new GridLayout(2, 5, 10, 10));
        cards.setBackground(new Color(30, 34, 42));

        activeLabel = createStatCard(cards, "Active Workflows", "0", new Color(52, 152, 219));
        completedLabel = createStatCard(cards, "Completed", "0", new Color(46, 204, 113));
        failedLabel = createStatCard(cards, "Failed", "0", new Color(231, 76, 60));
        retryingLabel = createStatCard(cards, "Retrying", "0", new Color(241, 196, 15));
        waitingLabel = createStatCard(cards, "Waiting", "0", new Color(155, 89, 182));

        queuedLabel = createStatCard(cards, "Queued Tasks", "0", new Color(52, 73, 94));
        workersLabel = createStatCard(cards, "Active Workers", "0", new Color(26, 188, 156));
        eventRateLabel = createStatCard(cards, "Events/sec", "0", new Color(52, 152, 219));
        avgExecLabel = createStatCard(cards, "Avg Exec Time", "0ms", new Color(26, 188, 156));
        failureRateLabel = createStatCard(cards, "Failure Rate", "0%", new Color(231, 76, 60));

        panel.add(cards, BorderLayout.NORTH);

        // Center: graphs
        JPanel centerPanel = new JPanel(new GridLayout(1, 2, 10, 10));
        centerPanel.setBackground(new Color(30, 34, 42));
        graphPanel = new MetricsGraphPanel();
        graphPanel.setBorder(new TitledBorder(new LineBorder(new Color(60, 70, 80)),
            "Real-Time Metrics", 0, 0, new Font("SansSerif", Font.BOLD, 12), Color.WHITE));
        centerPanel.add(graphPanel);

        JPanel actionStatsPanel = new JPanel(new BorderLayout());
        actionStatsPanel.setBackground(new Color(38, 42, 52));
        actionStatsPanel.setBorder(new TitledBorder(new LineBorder(new Color(60, 70, 80)),
            "Action Performance", 0, 0, new Font("SansSerif", Font.BOLD, 12), Color.WHITE));
        eventLogArea = new JTextArea();
        eventLogArea.setEditable(false);
        eventLogArea.setBackground(new Color(30, 34, 42));
        eventLogArea.setForeground(new Color(200, 210, 220));
        eventLogArea.setFont(new Font("Monospaced", Font.PLAIN, 11));
        JScrollPane logScroll = new JScrollPane(eventLogArea);
        logScroll.setBorder(null);
        actionStatsPanel.add(logScroll, BorderLayout.CENTER);
        centerPanel.add(actionStatsPanel);

        panel.add(centerPanel, BorderLayout.CENTER);

        // Bottom: extra stats
        JPanel extraCards = new JPanel(new GridLayout(1, 4, 10, 10));
        extraCards.setBackground(new Color(30, 34, 42));
        queueSizeLabel = createStatCard(extraCards, "Event Queue", "0", new Color(155, 89, 182));
        dupLabel = createStatCard(extraCards, "Duplicates Filtered", "0", new Color(241, 196, 15));
        successLabel = createStatCard(extraCards, "Total Success", "0", new Color(46, 204, 113));
        failLabel = createStatCard(extraCards, "Total Failures", "0", new Color(231, 76, 60));
        panel.add(extraCards, BorderLayout.SOUTH);

        return panel;
    }

    private JPanel createExecutionsTab() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBackground(new Color(30, 34, 42));

        String[] cols = {"Exec ID", "Workflow", "State", "Current Node", "Duration (ms)", "Retries", "Error"};
        executionTableModel = new DefaultTableModel(cols, 0) {
            @Override
            public boolean isCellEditable(int r, int c) { return false; }
        };
        executionTable = new JTable(executionTableModel);
        styleTable(executionTable);

        JScrollPane scroll = new JScrollPane(executionTable);
        scroll.setBackground(new Color(30, 34, 42));
        scroll.getViewport().setBackground(new Color(30, 34, 42));
        panel.add(scroll, BorderLayout.CENTER);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT));
        buttons.setBackground(new Color(30, 34, 42));
        JButton refresh = createStyledButton("🔄 Refresh", new Color(52, 152, 219));
        refresh.addActionListener(e -> refreshExecutionsTable());
        JButton cancel = createStyledButton("❌ Cancel Selected", new Color(231, 76, 60));
        cancel.addActionListener(e -> {
            int row = executionTable.getSelectedRow();
            if (row >= 0) {
                String execId = (String) executionTableModel.getValueAt(row, 0);
                engine.cancelWorkflow(execId);
            }
        });
        buttons.add(refresh);
        buttons.add(cancel);
        panel.add(buttons, BorderLayout.SOUTH);

        return panel;
    }

    private JPanel createEventsTab() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBackground(new Color(30, 34, 42));

        String[] cols = {"Event ID", "Type", "Source", "Tenant", "Idempotency Key", "Time"};
        eventTableModel = new DefaultTableModel(cols, 0) {
            @Override
            public boolean isCellEditable(int r, int c) { return false; }
        };
        eventTable = new JTable(eventTableModel);
        styleTable(eventTable);

        JScrollPane scroll = new JScrollPane(eventTable);
        scroll.getViewport().setBackground(new Color(30, 34, 42));
        panel.add(scroll, BorderLayout.CENTER);

        JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT));
        top.setBackground(new Color(30, 34, 42));
        JLabel lbl = new JLabel("Event Bus Activity (Live)");
        lbl.setForeground(Color.WHITE);
        lbl.setFont(new Font("SansSerif", Font.BOLD, 14));
        top.add(lbl);
        panel.add(top, BorderLayout.NORTH);

        return panel;
    }

    private JPanel createWorkersTab() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBackground(new Color(30, 34, 42));

        String[] cols = {"Worker ID", "State", "Current Task", "Last Task Time", "Tasks Done", "Tasks Failed"};
        workerTableModel = new DefaultTableModel(cols, 0) {
            @Override
            public boolean isCellEditable(int r, int c) { return false; }
        };
        workerTable = new JTable(workerTableModel);
        styleTable(workerTable);

        JScrollPane scroll = new JScrollPane(workerTable);
        scroll.getViewport().setBackground(new Color(30, 34, 42));
        panel.add(scroll, BorderLayout.CENTER);
        return panel;
    }

    private JPanel createFailureTab() {
        JPanel panel = new JPanel(new GridLayout(0, 2, 15, 15));
        panel.setBackground(new Color(30, 34, 42));
        panel.setBorder(new EmptyBorder(15, 15, 15, 15));

        // Failure injection buttons
        JPanel failPanel = new JPanel(new GridLayout(0, 2, 8, 8));
        failPanel.setBackground(new Color(38, 42, 52));
        failPanel.setBorder(new TitledBorder(new LineBorder(new Color(231, 76, 60), 2),
            "Failure Injection Controls", 0, 0, new Font("SansSerif", Font.BOLD, 14), new Color(231, 76, 60)));

        addToggleButton(failPanel, "💀 Kill Worker (random)", () -> {
            failureInjector.setKillWorker(!failureInjector.isKillWorkerEnabled());
            return failureInjector.isKillWorkerEnabled();
        });
        addToggleButton(failPanel, "🌊 Flood Events", () -> {
            failureInjector.setFloodEvents(!failureInjector.isFloodEventsEnabled());
            return failureInjector.isFloodEventsEnabled();
        });
        addToggleButton(failPanel, "📋 Duplicate Events", () -> {
            failureInjector.setDuplicateEvents(!failureInjector.isDuplicateEventsEnabled());
            return failureInjector.isDuplicateEventsEnabled();
        });
        addToggleButton(failPanel, "🐌 Slow Network", () -> {
            failureInjector.setSlowNetwork(!failureInjector.isSlowNetworkEnabled());
            return failureInjector.isSlowNetworkEnabled();
        });
        addToggleButton(failPanel, "💥 Random Failures", () -> {
            failureInjector.setRandomFailure(!failureInjector.isRandomFailureEnabled());
            return failureInjector.isRandomFailureEnabled();
        });
        addToggleButton(failPanel, "📛 Corrupt Events", () -> {
            failureInjector.setCorruptEvent(!failureInjector.isCorruptEventEnabled());
            return failureInjector.isCorruptEventEnabled();
        });
        addToggleButton(failPanel, "⏸ Pause Queue", () -> {
            failureInjector.setPauseQueue(!failureInjector.isPauseQueueEnabled());
            if (failureInjector.isPauseQueueEnabled()) workerPool.pause();
            else workerPool.resume();
            return failureInjector.isPauseQueueEnabled();
        });

        JButton resetAll = createStyledButton("🔄 Reset All", new Color(52, 152, 219));
        resetAll.addActionListener(e -> {
            failureInjector.resetAll();
            workerPool.resume();
        });
        failPanel.add(resetAll);

        panel.add(failPanel);

        // Failure log
        JPanel logPanel = new JPanel(new BorderLayout());
        logPanel.setBackground(new Color(38, 42, 52));
        logPanel.setBorder(new TitledBorder(new LineBorder(new Color(241, 196, 15), 2),
            "Failure Log", 0, 0, new Font("SansSerif", Font.BOLD, 14), new Color(241, 196, 15)));
        JTextArea failLog = new JTextArea(15, 30);
        failLog.setBackground(new Color(30, 34, 42));
        failLog.setForeground(new Color(220, 100, 100));
        failLog.setFont(new Font("Monospaced", Font.PLAIN, 11));
        failLog.setEditable(false);
        JScrollPane failScroll = new JScrollPane(failLog);
        logPanel.add(failScroll, BorderLayout.CENTER);

        // Will be populated by refresh
        JLabel placeholder = new JLabel("(Live updating...)");
        placeholder.setForeground(new Color(150, 160, 170));
        placeholder.setBorder(new EmptyBorder(10, 10, 10, 10));
        logPanel.add(placeholder, BorderLayout.NORTH);
        failLog.putClientProperty("placeholder", placeholder);
        panel.add(logPanel);

        return panel;
    }

    private JPanel createSimulationTab() {
        JPanel panel = new JPanel(new GridLayout(0, 2, 15, 15));
        panel.setBackground(new Color(30, 34, 42));
        panel.setBorder(new EmptyBorder(15, 15, 15, 15));

        JPanel configPanel = new JPanel(new GridLayout(0, 2, 8, 8));
        configPanel.setBackground(new Color(38, 42, 52));
        configPanel.setBorder(new TitledBorder(new LineBorder(new Color(46, 204, 113), 2),
            "Simulation Configuration", 0, 0, new Font("SansSerif", Font.BOLD, 14), new Color(46, 204, 113)));

        configPanel.add(label("Events/Second:", Color.WHITE));
        eventsPerSecField = new JTextField("20", 10);
        configPanel.add(eventsPerSecField);

        configPanel.add(label("Max Events:", Color.WHITE));
        maxEventsField = new JTextField("10000", 10);
        configPanel.add(maxEventsField);

        configPanel.add(label("Number of Workers:", Color.WHITE));
        numWorkersField = new JTextField("10", 10);
        configPanel.add(numWorkersField);

        configPanel.add(label("Max Retries:", Color.WHITE));
        maxRetriesField = new JTextField("3", 10);
        configPanel.add(maxRetriesField);

        configPanel.add(label("Rate Limit (events/sec):", Color.WHITE));
        rateLimitField = new JTextField("0", 10);
        configPanel.add(rateLimitField);

        JButton applyBtn = createStyledButton("Apply Config", new Color(46, 204, 113));
        applyBtn.addActionListener(e -> {
            try {
                int rate = Integer.parseInt(eventsPerSecField.getText());
                simulation.setEventsPerSecond(rate);
                int max = Integer.parseInt(maxEventsField.getText());
                simulation.setMaxEvents(max);
                int limit = Integer.parseInt(rateLimitField.getText());
                eventBus.setMaxRatePerSecond(limit);
                JOptionPane.showMessageDialog(this, "Config applied: " + rate + " events/s, max " + max);
            } catch (Exception ex) {
                JOptionPane.showMessageDialog(this, "Invalid number: " + ex.getMessage(),
                    "Error", JOptionPane.ERROR_MESSAGE);
            }
        });
        configPanel.add(applyBtn);
        panel.add(configPanel);

        // Event Publisher
        JPanel pubPanel = new JPanel(new BorderLayout(5, 5));
        pubPanel.setBackground(new Color(38, 42, 52));
        pubPanel.setBorder(new TitledBorder(new LineBorder(new Color(52, 152, 219), 2),
            "Manual Event Publisher", 0, 0, new Font("SansSerif", Font.BOLD, 14), new Color(52, 152, 219)));

        JPanel pubForm = new JPanel(new GridLayout(0, 2, 5, 5));
        pubForm.setBackground(new Color(38, 42, 52));
        JComboBox<EventType> typeBox = new JComboBox<>(EventType.values());
        pubForm.add(label("Type:", Color.WHITE));
        pubForm.add(typeBox);
        JTextField custIdField = new JTextField("CUST-00001");
        pubForm.add(label("Customer ID:", Color.WHITE));
        pubForm.add(custIdField);
        JTextField amtField = new JTextField("5000");
        pubForm.add(label("Amount:", Color.WHITE));
        pubForm.add(amtField);
        pubPanel.add(pubForm, BorderLayout.CENTER);

        JButton publish = createStyledButton("📤 Publish Event", new Color(52, 152, 219));
        publish.addActionListener(e -> {
            EventType et = (EventType) typeBox.getSelectedItem();
            Map<String, Object> payload = new HashMap<>();
            payload.put("customerId", custIdField.getText());
            payload.put("amount", Double.parseDouble(amtField.getText()));
            payload.put("age", 25);
            payload.put("status", "SUCCESS");
            payload.put("customerType", "PREMIUM");
            com.flowengine.domain.Event ev = new com.flowengine.domain.Event(UUID.randomUUID().toString(), et, "manual", "default",
                payload, "manual-" + System.currentTimeMillis());
            eventBus.publish(ev);
        });
        pubPanel.add(publish, BorderLayout.SOUTH);
        panel.add(pubPanel);

        return panel;
    }

    // --- Helper methods ---
    private JLabel createStatCard(JPanel parent, String title, String value, Color accent) {
        JPanel card = new JPanel(new BorderLayout(5, 5));
        card.setBackground(new Color(38, 42, 52));
        card.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(accent, 1),
            new EmptyBorder(10, 10, 10, 10)));
        JLabel titleLbl = new JLabel(title);
        titleLbl.setForeground(new Color(150, 160, 170));
        titleLbl.setFont(new Font("SansSerif", Font.PLAIN, 11));
        card.add(titleLbl, BorderLayout.NORTH);
        JLabel valueLbl = new JLabel(value);
        valueLbl.setForeground(accent);
        valueLbl.setFont(new Font("Monospaced", Font.BOLD, 22));
        card.add(valueLbl, BorderLayout.CENTER);
        parent.add(card);
        return valueLbl;
    }

    private JButton createStyledButton(String text, Color bg) {
        JButton b = new JButton(text);
        b.setBackground(bg);
        b.setForeground(Color.WHITE);
        b.setFocusPainted(false);
        b.setFont(new Font("SansSerif", Font.BOLD, 11));
        b.setBorder(new EmptyBorder(6, 12, 6, 12));
        b.setOpaque(true);
        return b;
    }

    private void addToggleButton(JPanel panel, String text, java.util.function.BooleanSupplier toggle) {
        JButton b = createStyledButton(text, new Color(60, 65, 75));
        b.addActionListener(e -> {
            boolean on = toggle.getAsBoolean();
            b.setBackground(on ? new Color(231, 76, 60) : new Color(46, 204, 113));
            b.setText(text + (on ? " [ON]" : " [OFF]"));
        });
        panel.add(b);
    }

    private JLabel label(String text, Color fg) {
        JLabel l = new JLabel(text);
        l.setForeground(fg);
        return l;
    }

    private void styleTable(JTable table) {
        table.setBackground(new Color(38, 42, 52));
        table.setForeground(Color.WHITE);
        table.setGridColor(new Color(60, 70, 80));
        table.setFont(new Font("SansSerif", Font.PLAIN, 11));
        table.getTableHeader().setBackground(new Color(45, 50, 60));
        table.getTableHeader().setForeground(Color.WHITE);
        table.getTableHeader().setFont(new Font("SansSerif", Font.BOLD, 11));
        table.setRowHeight(22);
        table.setSelectionBackground(new Color(0, 100, 180));
    }

    private void refreshAll() {
        try {
            // Metrics cards
            activeLabel.setText(String.valueOf(metrics.getActiveWorkflows()));
            completedLabel.setText(String.valueOf(metrics.getCompletedWorkflows()));
            failedLabel.setText(String.valueOf(metrics.getFailedWorkflows()));
            retryingLabel.setText(String.valueOf(metrics.getRetryingTasks()));
            waitingLabel.setText(String.valueOf(metrics.getWaitingTasks()));
            queuedLabel.setText(String.valueOf(metrics.getQueuedTasks()));
            workersLabel.setText(metrics.getActiveWorkers() + "/" + metrics.getTotalWorkers());
            eventRateLabel.setText(String.format("%.1f", metrics.getEventsPerSecond()));
            avgExecLabel.setText(String.format("%.0fms", metrics.getAvgExecutionTime()));
            failureRateLabel.setText(String.format("%.1f%%", metrics.getFailureRate()));
            queueSizeLabel.setText(String.valueOf(eventBus.getQueueSize()));
            dupLabel.setText(String.valueOf(eventBus.getTotalDuplicates()));

            int succ = metrics.getActionSuccesses().values().stream().mapToInt(AtomicInteger::get).sum();
            int fail = metrics.getActionFailures().values().stream().mapToInt(AtomicInteger::get).sum();
            successLabel.setText(String.valueOf(succ));
            failLabel.setText(String.valueOf(fail));

            graphPanel.setData(
                metrics.getEventsPerSecSeries(),
                metrics.getActiveWorkflowSeries(),
                metrics.getQueueSizeSeries()
            );

            refreshExecutionsTable();
            refreshEventTable();
            refreshWorkerTable();
            refreshFailureLog();
        } catch (Exception e) {
            // Ignore refresh errors
        }
    }

    private void refreshWorkflowList() {
        workflowListModel.clear();
        for (Workflow wf : engine.getAllWorkflows()) {
            workflowListModel.addElement(wf.getName() + " (" + wf.getId() + ")");
        }
    }

    private void refreshExecutionsTable() {
        executionTableModel.setRowCount(0);
        for (WorkflowExecution exec : engine.getAllExecutions()) {
            String currentNode = exec.getCurrentNodeId();
            if (currentNode != null && currentWorkflow != null) {
                WorkflowNode n = currentWorkflow.getNode(currentNode);
                if (n != null) currentNode = n.getName();
            }
            executionTableModel.addRow(new Object[]{
                exec.getExecutionId(),
                exec.getWorkflowId(),
                exec.getState().name(),
                currentNode != null ? currentNode : "—",
                exec.getDuration(),
                exec.getRetryCount().values().stream().mapToInt(Integer::intValue).sum(),
                exec.getLastError() != null ? exec.getLastError() : ""
            });
        }
    }

    private void refreshEventTable() {
        eventTableModel.setRowCount(0);
        List<com.flowengine.domain.Event> recent = eventBus.getRecentEvents(50);
        for (com.flowengine.domain.Event ev : recent) {
            eventTableModel.addRow(new Object[]{
                ev.getId().substring(0, Math.min(8, ev.getId().length())),
                ev.getType().name(),
                ev.getSource(),
                ev.getTenantId(),
                ev.getIdempotencyKey() != null ? ev.getIdempotencyKey() : "",
                new java.util.Date(ev.getTimestamp()).toString()
            });
        }
    }

    private void refreshWorkerTable() {
        workerTableModel.setRowCount(0);
        for (WorkerPool.WorkerStatus s : workerPool.getWorkerStatuses()) {
            workerTableModel.addRow(new Object[]{
                s.workerId,
                s.state != null ? s.state.name() : "IDLE",
                s.currentTask != null ? s.currentTask : "—",
                s.lastTaskTime > 0 ? new java.util.Date(s.lastTaskTime).toString() : "—",
                s.tasksCompleted,
                s.tasksFailed
            });
        }
    }

    private void refreshFailureLog() {
        if (eventLogArea == null) return;
        StringBuilder sb = new StringBuilder();
        for (String reason : metrics.getRecentFailureReasons()) {
            sb.append(reason).append("\n");
        }
        eventLogArea.setText(sb.toString());

        // Also update failure tab log
        for (Component c : mainTabs.getComponents()) {
            if (c instanceof JPanel p) updateFailureLog(p, failureInjector.getRecentFailures());
        }
    }

    private void updateFailureLog(Container parent, List<String> failures) {
        for (Component c : parent.getComponents()) {
            if (c instanceof JScrollPane sp) {
                Component view = sp.getViewport().getView();
                if (view instanceof JTextArea ta) {
                    StringBuilder sb = new StringBuilder();
                    for (String f : failures) sb.append(f).append("\n");
                    ta.setText(sb.toString());
                }
            } else if (c instanceof Container child) {
                updateFailureLog(child, failures);
            }
        }
    }

    private void createNewWorkflow() {
        String name = JOptionPane.showInputDialog(this, "Workflow name:", "New Workflow",
            JOptionPane.PLAIN_MESSAGE);
        if (name == null || name.isBlank()) return;
        Workflow wf = new Workflow("wf-" + System.currentTimeMillis(), name);
        WorkflowNode start = new WorkflowNode("start-" + System.currentTimeMillis(),
            NodeType.START, "Start", 50, 50);
        WorkflowNode end = new WorkflowNode("end-" + System.currentTimeMillis(),
            NodeType.END, "End", 50, 200);
        wf.addNode(start);
        wf.addNode(end);
        wf.setStartNodeId(start.getId());
        wf.setEndNodeId(end.getId());
        engine.registerWorkflow(wf);
        persistence.saveWorkflow(wf);
        refreshWorkflowList();
        JOptionPane.showMessageDialog(this, "Workflow created: " + name);
    }

    public void shutdown() {
        refreshTimer.cancel();
        simulation.shutdown();
        engine.shutdown();
        eventBus.shutdown();
        System.exit(0);
    }
}
