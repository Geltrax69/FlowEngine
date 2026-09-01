package com.flowengine.persistence;

import com.flowengine.domain.*;
import com.flowengine.engine.WorkflowEngine;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/**
 * Persists workflow definitions and execution logs to disk.
 * Uses simple JSON-like text format for human readability.
 */
public class PersistenceManager {

    private final String dataDir;

    public PersistenceManager(String dataDir) {
        this.dataDir = dataDir;
        try {
            Files.createDirectories(Paths.get(dataDir));
            Files.createDirectories(Paths.get(dataDir, "workflows"));
            Files.createDirectories(Paths.get(dataDir, "executions"));
        } catch (IOException e) {
            System.err.println("Failed to create data directories: " + e.getMessage());
        }
    }

    // --- Workflow Persistence ---
    public void saveWorkflow(Workflow workflow) {
        String path = dataDir + "/workflows/" + workflow.getId() + ".wf";
        try (PrintWriter w = new PrintWriter(new FileWriter(path))) {
            w.println("# Workflow Definition");
            w.println("id=" + workflow.getId());
            w.println("name=" + workflow.getName());
            w.println("description=" + workflow.getDescription());
            w.println("tenantId=" + workflow.getTenantId());
            w.println("version=" + workflow.getVersion());
            w.println("active=" + workflow.isActive());
            w.println("startNode=" + workflow.getStartNodeId());
            w.println("endNode=" + workflow.getEndNodeId());
            w.println();
            w.println("# Nodes");
            for (WorkflowNode node : workflow.getNodes().values()) {
                w.println("node|" + node.getId() + "|" + node.getType().name() + "|" +
                    node.getName() + "|" + (int) node.getX() + "|" + (int) node.getY() + "|" +
                    node.getWidth() + "|" + node.getHeight());
                for (Map.Entry<String, Object> cfg : node.getConfig().entrySet()) {
                    w.println("  cfg|" + cfg.getKey() + "=" + cfg.getValue());
                }
            }
            w.println();
            w.println("# Connections");
            for (Connection conn : workflow.getConnections().values()) {
                w.println("conn|" + conn.getId() + "|" + conn.getFromNodeId() + "|" +
                    conn.getToNodeId() + "|" + conn.getLabel() + "|" + conn.getCondition());
            }
            w.println();
            w.println("# Variables");
            for (Map.Entry<String, Object> v : workflow.getVariables().entrySet()) {
                w.println("var|" + v.getKey() + "=" + v.getValue());
            }
        } catch (IOException e) {
            System.err.println("Failed to save workflow: " + e.getMessage());
        }
    }

    public List<Workflow> loadWorkflows() {
        List<Workflow> result = new ArrayList<>();
        File dir = new File(dataDir + "/workflows");
        if (!dir.exists()) return result;
        for (File f : dir.listFiles((d, n) -> n.endsWith(".wf"))) {
            try {
                result.add(loadWorkflow(f));
            } catch (Exception e) {
                System.err.println("Failed to load workflow " + f.getName() + ": " + e.getMessage());
            }
        }
        return result;
    }

    private Workflow loadWorkflow(File file) throws IOException {
        Map<String, WorkflowNode> nodes = new LinkedHashMap<>();
        Map<String, Connection> connections = new LinkedHashMap<>();
        Workflow workflow = null;
        WorkflowNode currentNode = null;
        Map<String, Object> variables = new HashMap<>();
        String startNodeId = null, endNodeId = null;

        try (BufferedReader r = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = r.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                if (line.startsWith("id=")) {
                    String name = line.substring(3);
                    workflow = new Workflow(name, name);
                } else if (line.startsWith("name=") && workflow != null) {
                    workflow.setName(line.substring(5));
                } else if (line.startsWith("description=") && workflow != null) {
                    workflow.setDescription(line.substring(12));
                } else if (line.startsWith("startNode=")) {
                    startNodeId = line.substring(10);
                } else if (line.startsWith("endNode=")) {
                    endNodeId = line.substring(8);
                } else if (line.startsWith("node|")) {
                    String[] parts = line.substring(5).split("\\|");
                    String nid = parts[0];
                    NodeType type = NodeType.valueOf(parts[1]);
                    String name = parts[2];
                    double x = Double.parseDouble(parts[3]);
                    double y = Double.parseDouble(parts[4]);
                    WorkflowNode node = new WorkflowNode(nid, type, name, x, y);
                    nodes.put(nid, node);
                    currentNode = node;
                } else if (line.startsWith("  cfg|") && currentNode != null) {
                    String kv = line.substring(6);
                    int eq = kv.indexOf('=');
                    if (eq > 0) {
                        String k = kv.substring(0, eq);
                        String v = kv.substring(eq + 1);
                        try { currentNode.setConfig(k, Integer.parseInt(v)); }
                        catch (Exception e1) {
                            try { currentNode.setConfig(k, Double.parseDouble(v)); }
                            catch (Exception e2) { currentNode.setConfig(k, v); }
                        }
                    }
                } else if (line.startsWith("conn|")) {
                    String[] parts = line.substring(5).split("\\|", 5);
                    Connection conn = new Connection(parts[0], parts[1], parts[2], parts.length > 3 ? parts[3] : "");
                    if (parts.length > 4) conn.setCondition(parts[4]);
                    connections.put(conn.getId(), conn);
                } else if (line.startsWith("var|")) {
                    String kv = line.substring(4);
                    int eq = kv.indexOf('=');
                    if (eq > 0) variables.put(kv.substring(0, eq), kv.substring(eq + 1));
                }
            }
        }

        if (workflow == null) return null;
        workflow.getNodes().putAll(nodes);
        workflow.getConnections().putAll(connections);
        workflow.setStartNodeId(startNodeId);
        workflow.setEndNodeId(endNodeId);
        for (Connection c : connections.values()) {
            WorkflowNode from = nodes.get(c.getFromNodeId());
            WorkflowNode to = nodes.get(c.getToNodeId());
            if (from != null) from.addOutgoing(c);
            if (to != null) to.addIncoming(c);
        }
        workflow.getVariables().putAll(variables);
        return workflow;
    }

    // --- Execution Log ---
    public void logExecution(WorkflowExecution exec) {
        String path = dataDir + "/executions/" + exec.getExecutionId() + ".log";
        try (PrintWriter w = new PrintWriter(new FileWriter(path))) {
            w.println("# Execution Log");
            w.println("executionId=" + exec.getExecutionId());
            w.println("workflowId=" + exec.getWorkflowId());
            w.println("version=" + exec.getWorkflowVersion());
            w.println("tenantId=" + exec.getTenantId());
            w.println("startTime=" + exec.getStartTime());
            w.println("endTime=" + exec.getEndTime());
            w.println("state=" + exec.getState().name());
            w.println("duration=" + exec.getDuration() + "ms");
            if (exec.getLastError() != null) w.println("error=" + exec.getLastError());
            w.println();
            w.println("# History");
            for (NodeExecutionRecord rec : exec.getHistory()) {
                w.println("node|" + rec.getNodeId() + "|" + rec.getNodeType().name() + "|" +
                    rec.getTimestamp() + "|" + rec.isSuccess() + "|" + rec.getResult());
            }
            w.println();
            w.println("# Variables");
            for (Map.Entry<String, Object> v : exec.getVariables().entrySet()) {
                w.println("var|" + v.getKey() + "=" + v.getValue());
            }
        } catch (IOException e) {
            System.err.println("Failed to log execution: " + e.getMessage());
        }
    }

    public List<ExecutionLogEntry> loadExecutionLogs(int limit) {
        List<ExecutionLogEntry> entries = new ArrayList<>();
        File dir = new File(dataDir + "/executions");
        if (!dir.exists()) return entries;
        File[] files = dir.listFiles((d, n) -> n.endsWith(".log"));
        if (files == null) return entries;
        Arrays.sort(files, Comparator.comparingLong(File::lastModified).reversed());
        int count = 0;
        for (File f : files) {
            if (count++ >= limit) break;
            try {
                entries.add(loadExecutionLog(f));
            } catch (Exception e) {
                System.err.println("Failed to load log " + f.getName() + ": " + e.getMessage());
            }
        }
        return entries;
    }

    private ExecutionLogEntry loadExecutionLog(File file) throws IOException {
        ExecutionLogEntry entry = new ExecutionLogEntry();
        entry.fileName = file.getName();
        try (BufferedReader r = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = r.readLine()) != null) {
                line = line.trim();
                if (line.startsWith("executionId=")) entry.executionId = line.substring(12);
                else if (line.startsWith("workflowId=")) entry.workflowId = line.substring(11);
                else if (line.startsWith("state=")) entry.state = ExecutionState.valueOf(line.substring(6));
                else if (line.startsWith("startTime=")) entry.startTime = Long.parseLong(line.substring(10));
                else if (line.startsWith("endTime=")) entry.endTime = Long.parseLong(line.substring(8));
                else if (line.startsWith("duration=")) entry.duration = Long.parseLong(line.substring(9).replace("ms", ""));
                else if (line.startsWith("error=")) entry.error = line.substring(6);
            }
        }
        return entry;
    }

    public void purgeOldLogs(int maxFiles) {
        File dir = new File(dataDir + "/executions");
        if (!dir.exists()) return;
        File[] files = dir.listFiles((d, n) -> n.endsWith(".log"));
        if (files == null || files.length <= maxFiles) return;
        Arrays.sort(files, Comparator.comparingLong(File::lastModified));
        for (int i = 0; i < files.length - maxFiles; i++) {
            files[i].delete();
        }
    }

    public static class ExecutionLogEntry {
        public String fileName;
        public String executionId;
        public String workflowId;
        public ExecutionState state;
        public long startTime;
        public long endTime;
        public long duration;
        public String error;
    }
}
