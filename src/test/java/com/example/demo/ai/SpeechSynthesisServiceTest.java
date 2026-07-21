package com.example.demo.ai;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

class SpeechSynthesisServiceTest {

    @Test
    void wavParserIgnoresInvalidTrailingChunkAfterPcmData() throws Exception {
        byte[] wav = createWavWithInvalidTrailingChunk(new byte[] {1, 0, 2, 0});

        SpeechSynthesisService.SynthesizedAudio audio = assertDoesNotThrow(
                () -> SpeechSynthesisService.toWavAudio(wav, "\u6d4b\u8bd5\u6587\u672c"));

        assertArrayEquals(wav, audio.audioBytes());
        assertEquals(8000, audio.sampleRate());
        assertEquals(16, audio.bitsPerSample());
        assertEquals("\u6d4b\u8bd5\u6587\u672c", audio.transcript());
    }

    private byte[] createWavWithInvalidTrailingChunk(byte[] pcm) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        writeAscii(output, "RIFF");
        writeIntLe(output, 36 + pcm.length + 8);
        writeAscii(output, "WAVE");

        writeAscii(output, "fmt ");
        writeIntLe(output, 16);
        writeShortLe(output, 1);
        writeShortLe(output, 1);
        writeIntLe(output, 8000);
        writeIntLe(output, 16000);
        writeShortLe(output, 2);
        writeShortLe(output, 16);

        writeAscii(output, "data");
        writeIntLe(output, pcm.length);
        output.write(pcm);

        writeAscii(output, "JUNK");
        writeIntLe(output, 1024);
        return output.toByteArray();
    }

    private void writeAscii(ByteArrayOutputStream output, String value) throws IOException {
        output.write(value.getBytes(StandardCharsets.US_ASCII));
    }

    private void writeShortLe(ByteArrayOutputStream output, int value) {
        output.write(value & 0xff);
        output.write((value >>> 8) & 0xff);
    }

    private void writeIntLe(ByteArrayOutputStream output, int value) {
        output.write(value & 0xff);
        output.write((value >>> 8) & 0xff);
        output.write((value >>> 16) & 0xff);
        output.write((value >>> 24) & 0xff);
    }
}
