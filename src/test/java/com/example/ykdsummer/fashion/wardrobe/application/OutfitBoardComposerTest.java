package com.example.ykdsummer.fashion.wardrobe.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.List;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

class OutfitBoardComposerTest {

    @Test
    void composesAStableNonBlankBoardFromRealGarmentPixels() throws Exception {
        byte[] result = OutfitBoardComposer.compose(List.of(
                new OutfitBoardComposer.Source("TOP", solid(Color.RED, 260, 180)),
                new OutfitBoardComposer.Source("BOTTOM", solid(Color.BLUE, 180, 320))), 2);

        BufferedImage board = ImageIO.read(new ByteArrayInputStream(result));

        assertThat(board).isNotNull();
        assertThat(board.getWidth()).isEqualTo(900);
        assertThat(board.getHeight()).isEqualTo(1200);
        assertThat(countPixels(board, color -> color.getRed() > 180 && color.getGreen() < 120)).isPositive();
        assertThat(countPixels(board, color -> color.getBlue() > 180 && color.getGreen() < 160)).isPositive();
    }

    private static byte[] solid(Color color, int width, int height) throws Exception {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setColor(color);
        graphics.fillRect(0, 0, width, height);
        graphics.dispose();
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            ImageIO.write(image, "png", output);
            return output.toByteArray();
        }
    }

    private static int countPixels(
            BufferedImage image,
            java.util.function.Predicate<Color> predicate
    ) {
        int count = 0;
        for (int y = 0; y < image.getHeight(); y += 4) {
            for (int x = 0; x < image.getWidth(); x += 4) {
                if (predicate.test(new Color(image.getRGB(x, y)))) count++;
            }
        }
        return count;
    }
}
