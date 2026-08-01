package com.example.ykdsummer.fashion.application;

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

/** Deterministic fallback that keeps the real cutout pixels instead of synthesizing garment details. */
public final class OutfitBoardComposer {
    private static final int WIDTH = 900;
    private static final int HEIGHT = 1200;
    private static final int HEADER = 100;
    private static final int PADDING = 54;
    private static final int GAP = 24;

    private OutfitBoardComposer() { }

    public static byte[] compose(List<Source> sources, int rank) {
        List<Decoded> images = decode(sources);
        if (images.size() < 2) throw new IllegalArgumentException("搭配板至少需要两张有效图片");
        BufferedImage board = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = board.createGraphics();
        try {
            graphics.setColor(new Color(247, 247, 245));
            graphics.fillRect(0, 0, WIDTH, HEIGHT);
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            graphics.setColor(new Color(35, 38, 42));
            graphics.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 28));
            graphics.drawString("搭配 " + Math.max(1, rank), PADDING, 58);

            int availableHeight = HEIGHT - HEADER - PADDING;
            int cellHeight = (availableHeight - GAP * (images.size() - 1)) / images.size();
            int y = HEADER;
            for (Decoded decoded : images) {
                draw(graphics, decoded, y, cellHeight);
                y += cellHeight + GAP;
            }
        } finally {
            graphics.dispose();
        }
        return encode(board);
    }

    private static void draw(Graphics2D graphics, Decoded decoded, int y, int cellHeight) {
        int labelWidth = 110;
        int availableWidth = WIDTH - PADDING * 2 - labelWidth;
        int availableHeight = cellHeight - 20;
        BufferedImage image = decoded.image();
        double scale = Math.min(availableWidth / (double) image.getWidth(),
                availableHeight / (double) image.getHeight());
        int width = Math.max(1, (int) Math.round(image.getWidth() * scale));
        int height = Math.max(1, (int) Math.round(image.getHeight() * scale));
        int x = PADDING + labelWidth + (availableWidth - width) / 2;
        int imageY = y + (cellHeight - height) / 2;
        graphics.drawImage(image, x, imageY, width, height, null);
        graphics.setColor(new Color(90, 94, 100));
        graphics.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 20));
        graphics.drawString(label(decoded.role()), PADDING, y + cellHeight / 2);
    }

    private static List<Decoded> decode(List<Source> sources) {
        List<Decoded> decoded = new ArrayList<>();
        if (sources == null) return decoded;
        for (Source source : sources) {
            if (source == null || source.bytes() == null || source.bytes().length == 0) continue;
            try {
                BufferedImage image = ImageIO.read(new ByteArrayInputStream(source.bytes()));
                if (image != null) decoded.add(new Decoded(source.role(), image));
            } catch (IOException ignored) {
                // One broken source must not hide the other real wardrobe images.
            }
        }
        return decoded;
    }

    private static byte[] encode(BufferedImage image) {
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            if (!ImageIO.write(image, "png", output)) throw new IllegalStateException("无法编码搭配板");
            return output.toByteArray();
        } catch (IOException exception) {
            throw new IllegalStateException("无法生成搭配板", exception);
        }
    }

    private static String label(String role) {
        return switch (role == null ? "" : role.toUpperCase(java.util.Locale.ROOT)) {
            case "TOP" -> "上衣";
            case "BOTTOM" -> "下装";
            case "OUTERWEAR" -> "外套";
            default -> "单品";
        };
    }

    public record Source(String role, byte[] bytes) {
        public Source {
            bytes = bytes == null ? new byte[0] : bytes.clone();
        }
        @Override public byte[] bytes() { return bytes.clone(); }
    }

    private record Decoded(String role, BufferedImage image) { }
}
