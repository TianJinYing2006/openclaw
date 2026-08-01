package com.example.ykdsummer.fashion.application;

import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;

/** Produces one stable 2 x 2 wardrobe-preview page without invoking an image model. */
public final class FashionWardrobeContactSheet {
    public static final int PAGE_SIZE = 4;
    private static final int CELL_WIDTH = 360;
    private static final int CELL_HEIGHT = 440;
    private static final int PAGE_HEADER = 44;
    private static final int CELL_HEADER = 34;
    private static final int GAP = 12;
    private static final int PADDING = 16;
    private static final List<String> POSITIONS = List.of("左上", "右上", "左下", "右下");

    private FashionWardrobeContactSheet() { }

    public static byte[] composePage(List<byte[]> rawImages, int pageNumber) {
        List<BufferedImage> images = decode(rawImages);
        if (images.isEmpty()) throw new IllegalArgumentException("没有可展示的衣橱图片");
        int width = PADDING * 2 + CELL_WIDTH * 2 + GAP;
        int height = PADDING * 2 + PAGE_HEADER + CELL_HEIGHT * 2 + GAP;
        BufferedImage sheet = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = sheet.createGraphics();
        try {
            graphics.setColor(new Color(247, 248, 250));
            graphics.fillRect(0, 0, width, height);
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            graphics.setColor(new Color(42, 48, 56));
            graphics.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 20));
            graphics.drawString("衣橱预览 - 第 " + Math.max(1, pageNumber) + " 页", PADDING, PADDING + 25);
            for (int index = 0; index < images.size(); index++) {
                int column = index % 2;
                int row = index / 2;
                int x = PADDING + column * (CELL_WIDTH + GAP);
                int y = PADDING + PAGE_HEADER + row * (CELL_HEIGHT + GAP);
                drawCell(graphics, images.get(index), x, y, POSITIONS.get(index));
            }
        } finally {
            graphics.dispose();
        }
        return encode(sheet);
    }

    private static List<BufferedImage> decode(List<byte[]> rawImages) {
        List<BufferedImage> images = new ArrayList<>();
        if (rawImages == null) return images;
        for (byte[] bytes : rawImages) {
            if (bytes == null || bytes.length == 0 || images.size() >= PAGE_SIZE) continue;
            try {
                BufferedImage image = ImageIO.read(new ByteArrayInputStream(bytes));
                if (image != null) images.add(image);
            } catch (IOException ignored) {
                // A missing or unsupported image should not prevent the rest of the wardrobe from displaying.
            }
        }
        return images;
    }

    private static void drawCell(Graphics2D graphics, BufferedImage image, int x, int y, String position) {
        graphics.setColor(Color.WHITE);
        graphics.fillRoundRect(x, y, CELL_WIDTH, CELL_HEIGHT, 8, 8);
        graphics.setColor(new Color(214, 218, 224));
        graphics.drawRoundRect(x, y, CELL_WIDTH, CELL_HEIGHT, 8, 8);
        graphics.setColor(new Color(42, 48, 56));
        graphics.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 18));
        graphics.drawString(position, x + 14, y + 23);

        int availableWidth = CELL_WIDTH - PADDING * 2;
        int availableHeight = CELL_HEIGHT - CELL_HEADER - PADDING * 2;
        double scale = Math.min(availableWidth / (double) image.getWidth(), availableHeight / (double) image.getHeight());
        int width = Math.max(1, (int) Math.round(image.getWidth() * scale));
        int height = Math.max(1, (int) Math.round(image.getHeight() * scale));
        int imageX = x + (CELL_WIDTH - width) / 2;
        int imageY = y + CELL_HEADER + (availableHeight - height) / 2;
        graphics.drawImage(image, imageX, imageY, width, height, null);
    }

    private static BufferedImage copy(BufferedImage image) {
        BufferedImage copy = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = copy.createGraphics();
        try {
            graphics.setComposite(AlphaComposite.SrcOver);
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, copy.getWidth(), copy.getHeight());
            graphics.drawImage(image, 0, 0, null);
        } finally {
            graphics.dispose();
        }
        return copy;
    }

    private static byte[] encode(BufferedImage image) {
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            if (!ImageIO.write(image, "png", output)) throw new IllegalStateException("无法编码衣橱展示图");
            return output.toByteArray();
        } catch (IOException exception) {
            throw new IllegalStateException("无法生成衣橱展示图", exception);
        }
    }
}
