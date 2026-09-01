package com.flowengine.ui;

import com.flowengine.domain.*;
import com.flowengine.engine.WorkflowEngine;

import javax.swing.*;
import java.awt.*;
import java.awt.geom.*;
import java.awt.event.*;
import java.util.*;
import java.util.List;
import java.util.function.Consumer;

/**
 * Visual workflow canvas — the heart of the workflow builder.
 * Supports drag-and-drop nodes, edge connections, selection, deletion, and zoom/pan.
 */
public class WorkflowCanvas extends JPanel {

    // --- Workflow being edited ---
    private Workflow workflow;
    private final Consumer<Workflow> onWorkflowChanged;

    // --- Interaction state ---
    private NodeType selectedNodeType = NodeType.ACTION;
    private Point dragStart;
    private WorkflowNode draggedNode;
    private Point offsetInNode;
    private WorkflowNode selectedNode;
    private Point connectionStart; // node center where connection starts
    private String connectionStartNodeId;
    private WorkflowNode connectionEndNode; // hover target
    private Rectangle selectionRect;
    private Point panStart;
    private double scale = 1.0;
    private Point translate = new Point(0, 0);

    // --- Drawing constants ---
    private static final Color GRID_COLOR = new Color(40, 44, 52);
    private static final Color NODE_BORDER = new Color(60, 70, 80);
    private static final Color CONNECTION_COLOR = new Color(100, 120, 150);
    private static final Color SELECTION_COLOR = new Color(0, 120, 215);
    private static final Color CONNECTION_ACTIVE = new Color(0, 180, 255);

    // --- Fonts ---
    private final Font nodeFont = new Font("SansSerif", Font.BOLD, 11);
    private final Font typeFont = new Font("SansSerif", Font.PLAIN, 9);
    private final Font labelFont = new Font("SansSerif", Font.ITALIC, 10);

    public WorkflowCanvas(Consumer<Workflow> onWorkflowChanged) {
        this.onWorkflowChanged = onWorkflowChanged;
        setBackground(new Color(30, 34, 42));
        setFocusable(true);
        enableInputMethods(true);

        MouseHandler mh = new MouseHandler();
        addMouseListener(mh);
        addMouseMotionListener(mh);
        addMouseWheelListener(e -> {
            scale = Math.max(0.25, Math.min(3.0, scale - e.getWheelRotation() * 0.1));
            repaint();
        });
    }

    public void setWorkflow(Workflow workflow) {
        this.workflow = workflow;
        this.selectedNode = null;
        repaint();
    }

    public void setSelectedNodeType(NodeType type) { this.selectedNodeType = type; }

    public WorkflowNode getSelectedNode() { return selectedNode; }
    public void clearSelection() { selectedNode = null; repaint(); }

    public void deleteSelected() {
        if (selectedNode != null && workflow != null) {
            workflow.removeNode(selectedNode.getId());
            selectedNode = null;
            notifyChange();
            repaint();
        }
    }

    private void notifyChange() { if (onWorkflowChanged != null) onWorkflowChanged.accept(workflow); }

    // --- Coordinate transforms ---
    private Point screenToWorld(Point p) {
        return new Point((int) ((p.x - translate.x) / scale), (int) ((p.y - translate.y) / scale));
    }

    private Point worldToScreen(double wx, double wy) {
        return new Point((int) (wx * scale + translate.x), (int) (wy * scale + translate.y));
    }

    // --- Node lookup ---
    private WorkflowNode findNodeAt(Point wp) {
        if (workflow == null) return null;
        List<WorkflowNode> nodes = new ArrayList<>(workflow.getNodes().values());
        Collections.reverse(nodes); // top-most first
        for (WorkflowNode node : nodes) {
            if (wp.x >= node.getX() && wp.x <= node.getX() + node.getWidth() &&
                wp.y >= node.getY() && wp.y <= node.getY() + node.getHeight()) {
                return node;
            }
        }
        return null;
    }

    private String findConnectionTargetAt(Point wp) {
        if (workflow == null) return null;
        for (WorkflowNode node : workflow.getNodes().values()) {
            if (wp.x >= node.getX() && wp.x <= node.getX() + node.getWidth() &&
                wp.y >= node.getY() && wp.y <= node.getY() + node.getHeight()) {
                return node.getId();
            }
        }
        return null;
    }

    @Override
    protected void paintComponent(Graphics g0) {
        super.paintComponent(g0);
        Graphics2D g = (Graphics2D) g0;
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

        int w = getWidth(), h = getHeight();
        g.setColor(getBackground());
        g.fillRect(0, 0, w, h);

        if (workflow == null) {
            g.setColor(Color.GRAY);
            g.setFont(new Font("SansSerif", Font.ITALIC, 16));
            g.drawString("No workflow loaded", w / 2 - 80, h / 2);
            return;
        }

        g.translate(translate.x, translate.y);
        g.scale(scale, scale);

        drawGrid(g);
        drawConnections(g);
        drawConnectionPreview(g);
        drawSelectionRect(g);
        drawNodes(g);
        drawNodeLabels(g);

        g.scale(1 / scale, 1 / scale);
        g.translate(-translate.x, -translate.y);
        drawToolbar(g);
    }

    private void drawGrid(Graphics2D g) {
        g.setColor(new Color(35, 40, 50));
        int gridSize = 20;
        double worldW = getWidth() / scale + Math.abs(translate.x) / scale * 2;
        double worldH = getHeight() / scale + Math.abs(translate.y) / scale * 2;
        int startX = (int) Math.max(0, -(translate.x / scale) / gridSize * gridSize);
        int startY = (int) Math.max(0, -(translate.y / scale) / gridSize * gridSize);
        for (int x = startX; x < startX + worldW + gridSize; x += gridSize) {
            g.drawLine(x, startY, x, startY + (int) worldH + gridSize);
        }
        for (int y = startY; y < startY + worldH + gridSize; y += gridSize) {
            g.drawLine(startX, y, startX + (int) worldW + gridSize, y);
        }
    }

    private void drawConnections(Graphics2D g) {
        for (Connection conn : workflow.getConnections().values()) {
            WorkflowNode from = workflow.getNode(conn.getFromNodeId());
            WorkflowNode to = workflow.getNode(conn.getToNodeId());
            if (from == null || to == null) continue;

            double fx = from.getCenterX();
            double fy = from.getCenterY() + from.getHeight() / 2.0;
            double tx = to.getCenterX();
            double ty = to.getCenterY() - to.getHeight() / 2.0;

            Color color = isConnectionHighlighted(conn) ? CONNECTION_ACTIVE : CONNECTION_COLOR;
            g.setColor(color);
            g.setStroke(new BasicStroke(isConnectionHighlighted(conn) ? 3 : 2));

            QuadCurve2D.Double curve = new QuadCurve2D.Double(fx, fy, fx, (fy + ty) / 2, tx, ty);
            g.draw(curve);

            // Arrowhead
            drawArrow(g, tx, ty, tx, ty + 15, color);

            // Label
            if (!conn.getLabel().isEmpty()) {
                double midX = (fx + tx) / 2;
                double midY = (fy + ty) / 2;
                g.setColor(color);
                g.setFont(labelFont);
                String lbl = conn.getLabel();
                int lblW = g.getFontMetrics().stringWidth(lbl);
                g.fillRect((int) midX - lblW / 2 - 2, (int) midY - 8, lblW + 4, 14);
                g.setColor(new Color(30, 34, 42));
                g.drawString(lbl, (int) midX - lblW / 2, (int) midY + 3);
            }
        }
    }

    private boolean isConnectionHighlighted(Connection conn) {
        return selectedNode != null && selectedNode.getId().equals(conn.getFromNodeId());
    }

    private void drawArrow(Graphics2D g, double x, double y, double tx, double ty, Color color) {
        double angle = Math.atan2(ty - y, tx - x);
        int arrowLen = 10;
        g.setColor(color);
        g.setStroke(new BasicStroke(2));
        g.drawLine((int) x, (int) y,
            (int) (x - arrowLen * Math.cos(angle - Math.PI / 6)),
            (int) (y - arrowLen * Math.sin(angle - Math.PI / 6)));
        g.drawLine((int) x, (int) y,
            (int) (x - arrowLen * Math.cos(angle + Math.PI / 6)),
            (int) (y - arrowLen * Math.sin(angle + Math.PI / 6)));
    }

    private void drawConnectionPreview(Graphics2D g) {
        if (connectionStart != null && connectionEndNode != null) {
            double tx = connectionEndNode.getCenterX();
            double ty = connectionEndNode.getCenterY() - connectionEndNode.getHeight() / 2.0;
            g.setColor(CONNECTION_ACTIVE);
            g.setStroke(new BasicStroke(2, java.awt.BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 0, new float[]{8, 4}, 0));
            g.drawLine(connectionStart.x, connectionStart.y, (int) tx, (int) ty);
            g.setStroke(new BasicStroke(1));
        }
    }

    private void drawSelectionRect(Graphics2D g) {
        if (selectionRect != null) {
            g.setColor(SELECTION_COLOR);
            g.setStroke(new BasicStroke(1));
            g.drawRect(selectionRect.x, selectionRect.y, selectionRect.width, selectionRect.height);
            g.setColor(new Color(0, 120, 215, 30));
            g.fillRect(selectionRect.x, selectionRect.y, selectionRect.width, selectionRect.height);
        }
    }

    private void drawNodes(Graphics2D g) {
        for (WorkflowNode node : workflow.getNodes().values()) {
            drawNode(g, node, node == selectedNode);
        }
    }

    private void drawNode(Graphics2D g, WorkflowNode node, boolean selected) {
        int x = (int) node.getX();
        int y = (int) node.getY();
        int w = node.getWidth();
        int h = node.getHeight();

        // Shadow
        g.setColor(new Color(0, 0, 0, 60));
        g.fillRoundRect(x + 3, y + 3, w, h, 8, 8);

        // Background
        Color bg = parseHexColor(node.getType().color, new Color(50, 55, 65));
        if (selected) {
            bg = bg.darker();
        }
        g.setColor(bg);
        g.fillRoundRect(x, y, w, h, 8, 8);

        // Border
        g.setColor(selected ? SELECTION_COLOR : NODE_BORDER);
        g.setStroke(new BasicStroke(selected ? 3 : 1.5f));
        g.drawRoundRect(x, y, w, h, 8, 8);

        // Type indicator strip
        Color stripColor = parseHexColor(node.getType().color, Color.CYAN);
        g.setColor(stripColor);
        g.fillRoundRect(x, y, w, 6, 8, 8);
        g.fillRect(x + 4, y + 3, w - 8, 3);

        // Icon area
        g.setColor(Color.WHITE);
        g.setFont(nodeFont);
        String typeLabel = node.getType().label;
        int typeW = g.getFontMetrics().stringWidth(typeLabel);
        g.drawString(typeLabel, x + (w - typeW) / 2, y + 20);

        // Node name
        g.setColor(new Color(200, 210, 220));
        g.setFont(typeFont);
        String name = node.getName();
        if (name.length() > 14) name = name.substring(0, 12) + "…";
        int nameW = g.getFontMetrics().stringWidth(name);
        g.drawString(name, x + (w - nameW) / 2, y + 38);

        // Status indicators
        if (node.getType() == NodeType.EVENT) {
            g.setColor(Color.YELLOW);
            g.setFont(new Font("SansSerif", Font.BOLD, 16));
            g.drawString("▶", x + w - 16, y + 16);
        }
        if (node.getType() == NodeType.CONDITION) {
            g.setColor(Color.ORANGE);
            g.setFont(new Font("SansSerif", Font.BOLD, 14));
            String cond = (String) node.getConfig("expression");
            if (cond != null && cond.length() > 10) cond = cond.substring(0, 8) + "…";
            g.drawString("?", x + w - 14, y + 16);
        }
    }

    private void drawNodeLabels(Graphics2D g) {
        // Draw connection labels
        g.setColor(new Color(180, 190, 200));
        g.setFont(labelFont);
    }

    private void drawToolbar(Graphics2D g) {
        // Scale indicator
        g.setColor(Color.GRAY);
        g.setFont(new Font("SansSerif", Font.PLAIN, 10));
        g.drawString(String.format("%.0f%%", scale * 100), getWidth() - 40, getHeight() - 10);

        // Node type indicator
        if (workflow != null) {
            String hint = "Click to add " + selectedNodeType.label + " | Drag to connect | Del to delete | Scroll to zoom";
            g.setColor(new Color(100, 110, 120));
            g.drawString(hint, 8, getHeight() - 8);
        }
    }

    private Color parseHexColor(String hex, Color fallback) {
        try {
            return Color.decode(hex.startsWith("#") ? hex : "#" + hex);
        } catch (Exception e) { return fallback; }
    }

    // --- Mouse Handler ---
    private class MouseHandler extends MouseAdapter {
        private boolean isPanning = false;

        @Override
        public void mousePressed(MouseEvent e) {
            requestFocus();
            Point wp = screenToWorld(e.getPoint());
            WorkflowNode node = findNodeAt(wp);

            if (e.isControlDown()) {
                // Pan mode
                isPanning = true;
                panStart = e.getPoint();
                setCursor(Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR));
                return;
            }

            if (SwingUtilities.isRightMouseButton(e)) {
                // Start connection from right-click
                if (node != null) {
                    connectionStart = new Point((int) node.getCenterX(), (int) node.getCenterY());
                    connectionStartNodeId = node.getId();
                }
                return;
            }

            if (node != null) {
                selectedNode = node;
                draggedNode = node;
                dragStart = wp;
                offsetInNode = new Point(wp.x - (int) node.getX(), wp.y - (int) node.getY());
                setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            } else {
                selectedNode = null;
                // Click on empty canvas: add new node
                if (workflow != null) {
                    addNodeAt(wp);
                }
            }
            repaint();
        }

        @Override
        public void mouseDragged(MouseEvent e) {
            if (isPanning && panStart != null) {
                translate.x += e.getPoint().x - panStart.x;
                translate.y += e.getPoint().y - panStart.y;
                panStart = e.getPoint();
                repaint();
                return;
            }
            if (draggedNode != null) {
                Point wp = screenToWorld(e.getPoint());
                draggedNode.setX(wp.x - offsetInNode.x);
                draggedNode.setY(wp.y - offsetInNode.y);
                repaint();
                return;
            }
            if (connectionStart != null) {
                // Update hover target
                Point wp = screenToWorld(e.getPoint());
                String targetId = findConnectionTargetAt(wp);
                connectionEndNode = targetId != null ? workflow.getNode(targetId) : null;
                repaint();
            }
        }

        @Override
        public void mouseReleased(MouseEvent e) {
            isPanning = false;
            setCursor(Cursor.getPredefinedCursor(Cursor.DEFAULT_CURSOR));

            if (draggedNode != null) {
                draggedNode = null;
                dragStart = null;
                offsetInNode = null;
                notifyChange();
                repaint();
                return;
            }

            if (connectionStart != null) {
                Point wp = screenToWorld(e.getPoint());
                String targetId = findConnectionTargetAt(wp);
                if (targetId != null && !targetId.equals(connectionStartNodeId)) {
                    // Create connection
                    String connId = "conn-" + System.currentTimeMillis();
                    Connection conn = new Connection(connId, connectionStartNodeId, targetId);
                    workflow.addConnection(conn);
                    notifyChange();
                }
                connectionStart = null;
                connectionStartNodeId = null;
                connectionEndNode = null;
                repaint();
            }
        }

        @Override
        public void mouseClicked(MouseEvent e) {
            if (e.getClickCount() == 2) {
                Point wp = screenToWorld(e.getPoint());
                WorkflowNode node = findNodeAt(wp);
                if (node != null) {
                    openNodeEditor(node);
                }
            }
        }

        private void addNodeAt(Point wp) {
            String id = "node-" + System.currentTimeMillis();
            WorkflowNode node = new WorkflowNode(id, selectedNodeType, selectedNodeType.label,
                wp.x - 80, wp.y - 30);
            workflow.addNode(node);
            selectedNode = node;
            notifyChange();
            repaint();
        }
    }

    private void openNodeEditor(WorkflowNode node) {
        JDialog dialog = new JDialog((Frame) SwingUtilities.getWindowAncestor(this), "Edit Node", true);
        dialog.setSize(400, 350);
        dialog.setLocationRelativeTo(this);

        JPanel content = new JPanel(new GridLayout(0, 2, 5, 5));
        content.setBorder(BorderFactory.createEmptyBorder(15, 15, 15, 15));
        content.setBackground(new Color(38, 42, 52));

        content.add(label("Name:", Color.WHITE));
        JTextField nameField = new JTextField(node.getName());
        nameField.setBackground(new Color(50, 55, 65));
        nameField.setForeground(Color.WHITE);
        nameField.setCaretColor(Color.WHITE);
        content.add(nameField);

        content.add(label("Type:", Color.WHITE));
        JComboBox<NodeType> typeBox = new JComboBox<>(NodeType.values());
        typeBox.setSelectedItem(node.getType());
        typeBox.setBackground(new Color(50, 55, 65));
        typeBox.setForeground(Color.WHITE);
        content.add(typeBox);

        // Dynamic config fields (declared up front for closure access)
        final JTextField exprField;
        final JComboBox<ActionType> actionBox;
        final JTextField delayField;
        final JTextField approverField;
        final JTextField retriesField;

        if (node.getType() == NodeType.EVENT || node.getType() == NodeType.CONDITION ||
            node.getType() == NodeType.ACTION) {
            exprField = new JTextField(node.getConfig("expression") != null ?
                String.valueOf(node.getConfig("expression")) : "");
            exprField.setBackground(new Color(50, 55, 65));
            exprField.setForeground(Color.WHITE);
            exprField.setCaretColor(Color.WHITE);
            content.add(label("Expression:", Color.WHITE));
            content.add(exprField);
        } else {
            exprField = null;
        }

        if (node.getType() == NodeType.ACTION) {
            ActionType[] actions = ActionType.values();
            actionBox = new JComboBox<>(actions);
            String current = (String) node.getConfig("actionType");
            if (current != null) {
                try { actionBox.setSelectedItem(ActionType.valueOf(current)); }
                catch (Exception ignored) {}
            }
            actionBox.setBackground(new Color(50, 55, 65));
            actionBox.setForeground(Color.WHITE);
            content.add(label("Action Type:", Color.WHITE));
            content.add(actionBox);
        } else {
            actionBox = null;
        }

        if (node.getType() == NodeType.DELAY) {
            delayField = new JTextField(
                node.getConfig("delayMs") != null ? String.valueOf(node.getConfig("delayMs")) : "1000");
            delayField.setBackground(new Color(50, 55, 65));
            delayField.setForeground(Color.WHITE);
            delayField.setCaretColor(Color.WHITE);
            content.add(label("Delay (ms):", Color.WHITE));
            content.add(delayField);
        } else {
            delayField = null;
        }

        if (node.getType() == NodeType.APPROVAL) {
            approverField = new JTextField(
                node.getConfig("approver") != null ? String.valueOf(node.getConfig("approver")) : "admin");
            approverField.setBackground(new Color(50, 55, 65));
            approverField.setForeground(Color.WHITE);
            approverField.setCaretColor(Color.WHITE);
            content.add(label("Approver:", Color.WHITE));
            content.add(approverField);
        } else {
            approverField = null;
        }

        retriesField = new JTextField(
            node.getConfig("maxRetries") != null ? String.valueOf(node.getConfig("maxRetries")) : "3");
        retriesField.setBackground(new Color(50, 55, 65));
        retriesField.setForeground(Color.WHITE);
        retriesField.setCaretColor(Color.WHITE);
        content.add(label("Max Retries:", Color.WHITE));
        content.add(retriesField);

        dialog.add(content, BorderLayout.CENTER);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        buttons.setBackground(new Color(38, 42, 52));
        JButton saveBtn = new JButton("Save");
        saveBtn.addActionListener(ae -> {
            node.setName(nameField.getText());
            if (exprField != null && !exprField.getText().isBlank())
                node.setConfig("expression", exprField.getText());
            if (actionBox != null) {
                Object sel = actionBox.getSelectedItem();
                if (sel != null) node.setConfig("actionType", sel.toString());
            }
            if (delayField != null && !delayField.getText().isBlank())
                node.setConfig("delayMs", Long.parseLong(delayField.getText()));
            if (approverField != null && !approverField.getText().isBlank())
                node.setConfig("approver", approverField.getText());
            if (retriesField != null && !retriesField.getText().isBlank())
                node.setConfig("maxRetries", Integer.parseInt(retriesField.getText()));
            dialog.dispose();
            notifyChange();
            repaint();
        });
        JButton cancelBtn = new JButton("Cancel");
        cancelBtn.addActionListener(ae -> dialog.dispose());
        buttons.add(saveBtn);
        buttons.add(cancelBtn);
        dialog.add(buttons, BorderLayout.SOUTH);

        dialog.setVisible(true);
    }

    private JLabel label(String text, Color fg) {
        JLabel l = new JLabel(text);
        l.setForeground(fg);
        return l;
    }
}
