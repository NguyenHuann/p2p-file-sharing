package com.mycompany.p2pfilesharing.gui;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.RenderingHints;
import javax.swing.JPanel;

/**
 * JPanel bo tròn góc, có đổ bóng mềm, có thể viền nét đứt.
 * Kéo thả được trong NetBeans (sau khi Clean and Build).
 * Chỉnh trong Properties: arc (độ bo), borderColor (màu viền), dashed (nét đứt), shadow (đổ bóng).
 */
public class RoundPanel extends JPanel {

    private static final int M_SIDE = 3, M_TOP = 1, M_BOTTOM = 5; // vùng chừa cho bóng

    private int arc = 18;
    private Color borderColor = new Color(0xE4, 0xE7, 0xF5);
    private boolean dashed = false;
    private boolean shadow = true;

    public RoundPanel() {
        setOpaque(false);
        setBackground(Color.WHITE);
    }

    public int getArc() { return arc; }
    public void setArc(int arc) { this.arc = arc; repaint(); }

    public Color getBorderColor() { return borderColor; }
    public void setBorderColor(Color borderColor) { this.borderColor = borderColor; repaint(); }

    public boolean isDashed() { return dashed; }
    public void setDashed(boolean dashed) { this.dashed = dashed; repaint(); }

    public boolean isShadow() { return shadow; }
    public void setShadow(boolean shadow) { this.shadow = shadow; revalidate(); repaint(); }

    @Override
    public Insets getInsets() {
        Insets in = super.getInsets();
        if (!shadow) return in;
        return new Insets(in.top + M_TOP, in.left + M_SIDE, in.bottom + M_BOTTOM, in.right + M_SIDE);
    }

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        int x = 0, y = 0, w = getWidth() - 1, h = getHeight() - 1;
        if (shadow) {
            for (int i = 4; i >= 1; i--) {
                g2.setColor(new Color(60, 70, 140, 8));
                g2.fillRoundRect(M_SIDE - i + 1, M_TOP + i, getWidth() - 2 * M_SIDE + 2 * (i - 1), getHeight() - M_TOP - M_BOTTOM, arc + i, arc + i);
            }
            x = M_SIDE; y = M_TOP; w = getWidth() - 2 * M_SIDE - 1; h = getHeight() - M_TOP - M_BOTTOM - 1;
        }
        g2.setColor(getBackground());
        g2.fillRoundRect(x, y, w, h, arc, arc);
        if (borderColor != null) {
            g2.setColor(borderColor);
            if (dashed) {
                g2.setStroke(new BasicStroke(1.5f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER,
                        10f, new float[]{6f, 5f}, 0f));
            }
            g2.drawRoundRect(x, y, w, h, arc, arc);
        }
        g2.dispose();
        super.paintComponent(g);
    }
}
