package com.gravestonestudios.localmultiplayer;

import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;

public final class Player2AwtWindow {
    private static JFrame frame;
    private static ImagePanel panel;
    private static BufferedImage image;
    private static int imageWidth;
    private static int imageHeight;
    private static int[] argbPixels;

    private Player2AwtWindow() {
    }

    public static void update(ByteBuffer rgbaPixels, int width, int height) {
        if (rgbaPixels == null || width <= 0 || height <= 0) {
            return;
        }

        ensureImage(width, height);
        rgbaPixels.rewind();

        int index = 0;
        for (int y = height - 1; y >= 0; y--) {
            int rowOffset = y * width;
            for (int x = 0; x < width; x++) {
                int r = rgbaPixels.get() & 0xFF;
                int g = rgbaPixels.get() & 0xFF;
                int b = rgbaPixels.get() & 0xFF;
                int a = rgbaPixels.get() & 0xFF;
                argbPixels[rowOffset + x] = (a << 24) | (r << 16) | (g << 8) | b;
                index++;
            }
        }

        image.setRGB(0, 0, width, height, argbPixels, 0, width);

        SwingUtilities.invokeLater(() -> {
            ensureFrame(width, height);
            panel.repaint();
        });
    }

    private static void ensureImage(int width, int height) {
        if (image != null && imageWidth == width && imageHeight == height) {
            return;
        }

        imageWidth = width;
        imageHeight = height;
        image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        argbPixels = new int[width * height];
    }

    private static void ensureFrame(int width, int height) {
        if (frame != null) {
            return;
        }

        panel = new ImagePanel();
        panel.setPreferredSize(new Dimension(width, height));

        frame = new JFrame("Player 2 - Local Multiplayer");
        frame.setDefaultCloseOperation(JFrame.HIDE_ON_CLOSE);
        frame.setContentPane(panel);
        frame.pack();
        frame.setLocationByPlatform(true);
        frame.setVisible(true);
    }

    private static final class ImagePanel extends JPanel {
        @Override
        protected void paintComponent(Graphics graphics) {
            super.paintComponent(graphics);
            if (image != null) {
                graphics.drawImage(image, 0, 0, getWidth(), getHeight(), null);
            }
        }
    }
}
