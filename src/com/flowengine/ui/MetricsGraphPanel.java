package com.flowengine.ui;

import javax.swing.*;
import java.awt.*;
import java.awt.geom.Path2D;
import java.util.*;

/**
 * Real-time metrics visualization.
 * Renders line graphs for events/sec, active workflows, and queue size.
 */
public class MetricsGraphPanel extends JPanel {

    private double[] eventsPerSec = new double[60];
    private int[] activeWorkflows = new int[60];
    private int[] queueSize = new int[60];

    public MetricsGraphPanel() {
        setBackground(new Color(30, 34, 42));
        setPreferredSize(new Dimension(400, 200));
    }

    public void setData(double[] events, int[] workflows, int[] queues) {
        if (events != null) this.eventsPerSec = events;
        if (workflows != null) this.activeWorkflows = workflows;
        if (queues != null) this.queueSize = queues;
        repaint();
    }

    @Override
    protected void paintComponent(Graphics g0) {
        super.paintComponent(g0);
        Graphics2D g = (Graphics2D) g0;
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        int w = getWidth();
        int h = getHeight();
        g.setColor(new Color(30, 34, 42));
        g.fillRect(0, 0, w, h);

        // Draw three graphs
        int graphHeight = h / 3 - 5;
        drawGraph(g, 0, graphHeight, eventsPerSec, "Events/sec", new Color(52, 152, 219), 100.0);
        drawGraph(g, graphHeight + 5, graphHeight, toDouble(activeWorkflows), "Active Workflows", new Color(46, 204, 113), 50.0);
        drawGraph(g, (graphHeight + 5) * 2, graphHeight, toDouble(queueSize), "Queue Size", new Color(241, 196, 15), 100.0);
    }

    private double[] toDouble(int[] arr) {
        double[] result = new double[arr.length];
        for (int i = 0; i < arr.length; i++) result[i] = arr[i];
        return result;
    }

    private void drawGraph(Graphics2D g, int yOffset, int height, double[] data, String title, Color color, double maxScale) {
        int w = getWidth();
        int h = height;

        // Background
        g.setColor(new Color(40, 45, 55));
        g.fillRect(0, yOffset, w, h);

        // Title
        g.setColor(color);
        g.setFont(new Font("SansSerif", Font.BOLD, 11));
        g.drawString(title + " (peak: " + String.format("%.0f", maxValue(data)) + ")", 5, yOffset + 14);

        // Grid lines
        g.setColor(new Color(50, 55, 65));
        for (int i = 1; i < 4; i++) {
            int yLine = yOffset + h - (i * h / 4);
            g.drawLine(0, yLine, w, yLine);
        }

        // Find max for scaling
        double max = Math.max(maxScale, maxValue(data) * 1.1);
        if (max == 0) max = 1;

        // Data path
        if (data.length > 1) {
            Path2D.Double path = new Path2D.Double();
            for (int i = 0; i < data.length; i++) {
                double x = (double) i / (data.length - 1) * w;
                double y = yOffset + h - (data[i] / max) * (h - 20) - 10;
                if (i == 0) path.moveTo(x, y);
                else path.lineTo(x, y);
            }

            g.setStroke(new BasicStroke(2f));
            g.setColor(color);
            g.draw(path);

            // Fill area
            g.setColor(new Color(color.getRed(), color.getGreen(), color.getBlue(), 30));
            Path2D.Double fill = new Path2D.Double();
            fill.moveTo(0, yOffset + h);
            for (int i = 0; i < data.length; i++) {
                double x = (double) i / (data.length - 1) * w;
                double y = yOffset + h - (data[i] / max) * (h - 20) - 10;
                fill.lineTo(x, y);
            }
            fill.lineTo(w, yOffset + h);
            fill.closePath();
            g.fill(fill);
        }
    }

    private double maxValue(double[] data) {
        double max = 0;
        for (double d : data) max = Math.max(max, d);
        return max;
    }
}
