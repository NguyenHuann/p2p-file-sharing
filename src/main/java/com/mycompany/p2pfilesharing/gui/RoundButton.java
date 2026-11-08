package com.mycompany.p2pfilesharing.gui;

import java.awt.Color;
import java.awt.Cursor;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import javax.swing.JButton;

/**
 * JButton bo tròn góc, phẳng. filled=false thì không tô nền (dùng cho menu không active).
 */
public class RoundButton extends JButton {

    private int arc = 18;
    private boolean filled = true;
    private Color borderColor = null;

    public RoundButton() {
        setContentAreaFilled(false);
        setBorderPainted(false);
        setFocusPainted(false);
        setMargin(new java.awt.Insets(0, 0, 0, 0));
        setRolloverEnabled(true);
        setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
    }

    public int getArc() { return arc; }
    public void setArc(int arc) { this.arc = arc; repaint(); }

    public boolean isFilled() { return filled; }
    public void setFilled(boolean filled) { this.filled = filled; repaint(); }

    public Color getBorderColor() { return borderColor; }
    public void setBorderColor(Color borderColor) { this.borderColor = borderColor; repaint(); }

    /** Hover: nền sáng thì tối đi chút, nền tối thì sáng lên chút. */
    private static Color hover(Color c) {
        int lum = (c.getRed() * 299 + c.getGreen() * 587 + c.getBlue() * 114) / 1000;
        return lum > 160 ? new Color(Math.max(0, c.getRed() - 10), Math.max(0, c.getGreen() - 10), Math.max(0, c.getBlue() - 6))
                         : c.brighter();
    }

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        Color bg = getBackground();
        if (getModel().isPressed()) bg = bg.darker();
        else if (getModel().isRollover()) bg = hover(bg);
        if (!filled && (getModel().isRollover() || getModel().isPressed())) {
            g2.setColor(new Color(0xE9, 0xEB, 0xFC));
            g2.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, arc, arc);
        }
        if (filled) {
            g2.setColor(bg);
            g2.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, arc, arc);
        }
        if (borderColor != null) {
            g2.setColor(borderColor);
            g2.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, arc, arc);
        }
        g2.dispose();
        super.paintComponent(g);
    }
}
