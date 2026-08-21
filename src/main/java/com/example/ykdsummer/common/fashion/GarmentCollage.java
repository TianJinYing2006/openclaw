package com.example.ykdsummer.common.fashion;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import javax.imageio.ImageIO;

/**
 * 单品图拼图工具（中性共用层）。
 *
 * <p>把两张单品图（上衣 + 下装）上下拼接成一张穿搭拼图，供虚拟试衣 / 参考图发送复用。
 * 位于 {@code common.fashion} 共用包，不依赖 {@code ai.fashion}（Look 引擎）也不依赖
 * {@code fashion} / {@code fashion.wardrobe}（衣橱引擎）。原先该拼图逻辑内联在
 * {@code ai.fashion.FashionAgentService} 中，被衣橱引擎的 {@code FashionTryOnTools} 直接调用，
 * 形成 {@code fashion → ai.fashion} 的反向依赖；下沉到本类后，两引擎都改为依赖共用层。
 *
 * <p>任一图片无法解码时返回 {@code null}，由调用方降级为逐张发送。
 */
public final class GarmentCollage {

    private GarmentCollage() {
        // 纯工具类，禁止实例化
    }

    /** 把两张单品图上下拼接成一张 PNG 拼图；任一图片无法解码时返回 null（调用方降级逐张发送）。 */
    public static byte[] buildGarmentCollage(byte[] top, byte[] bottom) {
        if (top == null || top.length == 0 || bottom == null || bottom.length == 0) {
            return null;
        }
        try {
            BufferedImage topImage = ImageIO.read(new ByteArrayInputStream(top));
            BufferedImage bottomImage = ImageIO.read(new ByteArrayInputStream(bottom));
            if (topImage == null || bottomImage == null) {
                return null;
            }
            int width = Math.max(topImage.getWidth(), bottomImage.getWidth());
            BufferedImage topScaled = scaleToWidth(topImage, width);
            BufferedImage bottomScaled = scaleToWidth(bottomImage, width);
            int height = topScaled.getHeight() + bottomScaled.getHeight();
            BufferedImage collage = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
            Graphics2D graphics = collage.createGraphics();
            try {
                graphics.setColor(Color.WHITE);
                graphics.fillRect(0, 0, width, height);
                graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                        RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                graphics.drawImage(topScaled, 0, 0, null);
                graphics.drawImage(bottomScaled, 0, topScaled.getHeight(), null);
            } finally {
                graphics.dispose();
            }
            try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                if (!ImageIO.write(collage, "png", output)) {
                    return null;
                }
                return output.toByteArray();
            }
        } catch (IOException | RuntimeException exception) {
            return null;
        }
    }

    /** 等比缩放到目标宽度；已是目标宽度时原样返回，避免无谓重采样。 */
    private static BufferedImage scaleToWidth(BufferedImage image, int targetWidth) {
        if (image.getWidth() == targetWidth) {
            return image;
        }
        int height = Math.max(1, (int) Math.round(image.getHeight() * (double) targetWidth / image.getWidth()));
        BufferedImage scaled = new BufferedImage(targetWidth, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = scaled.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            graphics.drawImage(image, 0, 0, targetWidth, height, null);
        } finally {
            graphics.dispose();
        }
        return scaled;
    }
}
