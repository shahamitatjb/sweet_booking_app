package com.jb.service;

import com.google.zxing.BinaryBitmap;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.client.j2se.BufferedImageLuminanceSource;
import com.google.zxing.common.HybridBinarizer;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;

import static org.assertj.core.api.Assertions.assertThat;

class QrImageServiceTest {

    @Test
    void rendersAPngThatDecodesBackToTheLink() throws Exception {
        String link = "https://example.org/v/JB-0123.abcDEF123";
        byte[] png = new QrImageService().png(link);

        assertThat(png).startsWith(new byte[] {(byte) 0x89, 'P', 'N', 'G'});
        BufferedImage img = ImageIO.read(new ByteArrayInputStream(png));
        assertThat(img.getWidth()).isGreaterThanOrEqualTo(300);
        String decoded = new MultiFormatReader()
                .decode(new BinaryBitmap(new HybridBinarizer(new BufferedImageLuminanceSource(img)))).getText();
        assertThat(decoded).isEqualTo(link);
    }
}
