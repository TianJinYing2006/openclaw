package com.example.ykdsummer.fashion.wardrobe.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

class FashionWardrobeContactSheetTest {

    @Test
    void composesSeveralWardrobeImagesIntoOnePreview() throws IOException {
        byte[] result = FashionWardrobeContactSheet.composePage(List.of(image(Color.BLUE), image(Color.RED)), 2);

        BufferedImage sheet = ImageIO.read(new ByteArrayInputStream(result));
        assertThat(result).isNotEmpty();
        assertThat(sheet).isNotNull();
        assertThat(sheet.getWidth()).isGreaterThan(700);
        assertThat(sheet.getHeight()).isGreaterThan(900);
    }

    private static byte[] image(Color color) throws IOException {
        BufferedImage image = new BufferedImage(80, 120, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setColor(color);
            graphics.fillRect(0, 0, image.getWidth(), image.getHeight());
        } finally {
            graphics.dispose();
        }
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            ImageIO.write(image, "png", output);
            return output.toByteArray();
        }
    }
}
