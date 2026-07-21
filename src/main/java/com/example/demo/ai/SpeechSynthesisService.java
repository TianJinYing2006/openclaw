package com.example.demo.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

@Service
public class SpeechSynthesisService {

    private static final Logger log = LoggerFactory.getLogger(SpeechSynthesisService.class);

    private static final String DEFAULT_TTS_URL =
            "https://dashscope.aliyuncs.com/api/v1/services/aigc/multimodal-generation/generation";
    private static final String DEFAULT_MODEL = "qwen3-tts-flash";
    private static final String DEFAULT_VOICE = "Cherry";
    private static final String DEFAULT_LANGUAGE = "Chinese";
    private static final int DEFAULT_MAX_CHARS = 400;
    private static final int DEFAULT_SAMPLE_RATE = 24000;
    private static final int DEFAULT_BITS_PER_SAMPLE = 16;
    private static final int DEFAULT_CHANNELS = 1;

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    @Value("${llm.api-key:}")
    private String apiKey;

    @Value("${llm.tts-api-url:}")
    private String apiUrl;

    @Value("${llm.tts-model:qwen3-tts-flash}")
    private String model;

    @Value("${llm.tts-voice:Cherry}")
    private String voice;

    @Value("${llm.tts-language:Chinese}")
    private String language;

    @Value("${llm.tts-max-chars:400}")
    private int maxChars;

    @Value("${llm.tts-sample-rate:24000}")
    private int sampleRate;

    public SpeechSynthesisService() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        this.objectMapper = new ObjectMapper();
    }

    public List<SynthesizedAudio> synthesize(String text) throws IOException, InterruptedException {
        return synthesize(text, null);
    }

    public List<SynthesizedAudio> synthesize(String text, String requestedVoice)
            throws IOException, InterruptedException {
        String normalizedText = text == null ? "" : text.trim();
        if (normalizedText.isEmpty()) {
            throw new IllegalArgumentException("TTS text must not be empty");
        }

        String actualVoice = valueOrDefault(requestedVoice, valueOrDefault(voice, DEFAULT_VOICE));
        List<SynthesizedAudio> results = new ArrayList<>();
        for (String segment : splitText(normalizedText)) {
            results.add(synthesizeSegment(segment, segment, actualVoice));
        }
        return results;
    }

    private SynthesizedAudio synthesizeSegment(String text, String transcript, String requestedVoice)
            throws IOException, InterruptedException {
        String key = requireNonBlank(apiKey, "llm.api-key is not configured");

        log.info("DashScope TTS request: model={}, voice={}, chars={}",
                valueOrDefault(model, DEFAULT_MODEL), requestedVoice, text.length());

        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", valueOrDefault(model, DEFAULT_MODEL));
        ObjectNode input = body.putObject("input");
        input.put("text", text);
        input.put("voice", requestedVoice);
        input.put("language_type", valueOrDefault(language, DEFAULT_LANGUAGE));

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(valueOrDefault(apiUrl, DEFAULT_TTS_URL)))
                .header("Authorization", "Bearer " + key)
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(90))
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IOException("DashScope TTS failed: HTTP " + response.statusCode() + ", " + response.body());
        }

        JsonNode root = objectMapper.readTree(response.body());
        int statusCode = root.path("status_code").asInt(200);
        if (statusCode != 200) {
            String code = root.path("code").asText("");
            String message = root.path("message").asText("");
            throw new IOException("DashScope TTS failed: status=" + statusCode
                    + ", code=" + code + ", message=" + message);
        }

        JsonNode audio = root.path("output").path("audio");
        byte[] audioBytes = readAudioBytes(audio);
        return toWavAudio(audioBytes, transcript);
    }

    private byte[] readAudioBytes(JsonNode audio) throws IOException, InterruptedException {
        if (audio == null || audio.isMissingNode() || audio.isNull()) {
            throw new IOException("DashScope TTS response has no audio field");
        }

        String data = audio.path("data").asText("").trim();
        if (!data.isBlank()) {
            byte[] bytes = Base64.getDecoder().decode(stripDataUrlPrefix(data));
            if (isWav(bytes)) {
                return bytes;
            }
            return wrapPcmAsWav(bytes, configuredSampleRate(), DEFAULT_CHANNELS, DEFAULT_BITS_PER_SAMPLE);
        }

        String url = audio.path("url").asText("").trim();
        if (!url.isBlank()) {
            return downloadAudio(url);
        }

        throw new IOException("DashScope TTS response has empty audio data and url");
    }

    private byte[] downloadAudio(String url) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(90))
                .GET()
                .build();

        HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() != 200) {
            throw new IOException("Download TTS audio failed: HTTP " + response.statusCode());
        }
        return response.body();
    }

    public static SynthesizedAudio toWavAudio(byte[] audioBytes, String transcript) throws IOException {
        if (audioBytes == null || audioBytes.length == 0) {
            throw new IOException("TTS audio is empty");
        }
        if (!isWav(audioBytes)) {
            throw new IOException("TTS audio is not WAV");
        }

        WavMetadata metadata = readWavMetadata(audioBytes);
        int durationMs = estimateDurationMs(
                metadata.dataBytes(),
                metadata.sampleRate(),
                metadata.channels(),
                metadata.bitsPerSample());
        return new SynthesizedAudio(audioBytes, metadata.sampleRate(), metadata.bitsPerSample(), durationMs, transcript);
    }

    private static WavMetadata readWavMetadata(byte[] audioBytes) throws IOException {
        int offset = 12;
        int channels = DEFAULT_CHANNELS;
        int sampleRate = DEFAULT_SAMPLE_RATE;
        int bitsPerSample = DEFAULT_BITS_PER_SAMPLE;
        int dataBytes = 0;
        boolean foundFormat = false;

        while (offset + 8 <= audioBytes.length) {
            String chunkId = new String(audioBytes, offset, 4, StandardCharsets.US_ASCII);
            long chunkSize = readUnsignedInt(audioBytes, offset + 4);
            int chunkDataOffset = offset + 8;
            long chunkEnd = chunkDataOffset + chunkSize;
            if (chunkEnd > audioBytes.length) {
                if (dataBytes > 0) {
                    break;
                }
                chunkEnd = audioBytes.length;
                chunkSize = Math.max(0, chunkEnd - chunkDataOffset);
            }

            if ("fmt ".equals(chunkId)) {
                if (chunkSize < 16) {
                    throw new IOException("Invalid WAV fmt chunk");
                }
                channels = readUnsignedShort(audioBytes, chunkDataOffset + 2);
                sampleRate = readInt(audioBytes, chunkDataOffset + 4);
                bitsPerSample = readUnsignedShort(audioBytes, chunkDataOffset + 14);
                foundFormat = true;
            } else if ("data".equals(chunkId)) {
                dataBytes = (int) Math.min(chunkSize, Integer.MAX_VALUE);
            }

            offset = (int) chunkEnd;
            if ((offset & 1) == 1) {
                offset++;
            }
        }

        if (!foundFormat) {
            throw new IOException("WAV fmt chunk not found");
        }
        if (dataBytes <= 0) {
            throw new IOException("WAV data chunk not found");
        }
        return new WavMetadata(sampleRate, channels, bitsPerSample, dataBytes);
    }

    private static boolean isWav(byte[] audioBytes) {
        return audioBytes.length >= 12
                && audioBytes[0] == 'R'
                && audioBytes[1] == 'I'
                && audioBytes[2] == 'F'
                && audioBytes[3] == 'F'
                && audioBytes[8] == 'W'
                && audioBytes[9] == 'A'
                && audioBytes[10] == 'V'
                && audioBytes[11] == 'E';
    }

    private static int estimateDurationMs(int dataBytes, int sampleRate, int channels, int bitsPerSample) {
        if (sampleRate <= 0 || channels <= 0 || bitsPerSample <= 0) {
            return 0;
        }
        double bytesPerSecond = sampleRate * channels * (bitsPerSample / 8.0);
        return (int) Math.max(1, Math.round(dataBytes * 1000.0 / bytesPerSecond));
    }

    private List<String> splitText(String text) {
        int limit = maxChars > 0 ? maxChars : DEFAULT_MAX_CHARS;
        if (text.length() <= limit) {
            return List.of(text);
        }

        List<String> segments = new ArrayList<>();
        int start = 0;
        while (start < text.length()) {
            int end = Math.min(start + limit, text.length());
            int splitAt = findSplitPoint(text, start, end);
            if (splitAt <= start) {
                splitAt = end;
            }
            segments.add(text.substring(start, splitAt).trim());
            start = splitAt;
            while (start < text.length() && Character.isWhitespace(text.charAt(start))) {
                start++;
            }
        }
        return segments;
    }

    private int findSplitPoint(String text, int start, int end) {
        for (int index = end - 1; index > start; index--) {
            char ch = text.charAt(index);
            if (ch == '。' || ch == '！' || ch == '？' || ch == '.' || ch == '!' || ch == '?' || ch == '\n') {
                return index + 1;
            }
        }
        for (int index = end - 1; index > start; index--) {
            char ch = text.charAt(index);
            if (ch == '，' || ch == '、' || ch == ',' || ch == ';' || ch == '；') {
                return index + 1;
            }
        }
        return end;
    }

    private String requireNonBlank(String value, String errorMessage) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(errorMessage);
        }
        return value.trim();
    }

    private String valueOrDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private int configuredSampleRate() {
        return sampleRate > 0 ? sampleRate : DEFAULT_SAMPLE_RATE;
    }

    private static String stripDataUrlPrefix(String data) {
        int commaIndex = data.indexOf(',');
        if (data.startsWith("data:") && commaIndex >= 0) {
            return data.substring(commaIndex + 1);
        }
        return data;
    }

    private static byte[] wrapPcmAsWav(byte[] pcmBytes, int sampleRate, int channels, int bitsPerSample) {
        int byteRate = sampleRate * channels * bitsPerSample / 8;
        int blockAlign = channels * bitsPerSample / 8;
        int dataSize = pcmBytes.length;
        byte[] wavBytes = new byte[44 + dataSize];

        writeAscii(wavBytes, 0, "RIFF");
        writeIntLe(wavBytes, 4, 36 + dataSize);
        writeAscii(wavBytes, 8, "WAVE");
        writeAscii(wavBytes, 12, "fmt ");
        writeIntLe(wavBytes, 16, 16);
        writeShortLe(wavBytes, 20, 1);
        writeShortLe(wavBytes, 22, channels);
        writeIntLe(wavBytes, 24, sampleRate);
        writeIntLe(wavBytes, 28, byteRate);
        writeShortLe(wavBytes, 32, blockAlign);
        writeShortLe(wavBytes, 34, bitsPerSample);
        writeAscii(wavBytes, 36, "data");
        writeIntLe(wavBytes, 40, dataSize);
        System.arraycopy(pcmBytes, 0, wavBytes, 44, dataSize);
        return wavBytes;
    }

    private static int readUnsignedShort(byte[] bytes, int offset) {
        return (bytes[offset] & 0xff) | ((bytes[offset + 1] & 0xff) << 8);
    }

    private static int readInt(byte[] bytes, int offset) {
        return (bytes[offset] & 0xff)
                | ((bytes[offset + 1] & 0xff) << 8)
                | ((bytes[offset + 2] & 0xff) << 16)
                | ((bytes[offset + 3] & 0xff) << 24);
    }

    private static long readUnsignedInt(byte[] bytes, int offset) {
        return readInt(bytes, offset) & 0xffffffffL;
    }

    private static void writeAscii(byte[] bytes, int offset, String value) {
        byte[] valueBytes = value.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(valueBytes, 0, bytes, offset, valueBytes.length);
    }

    private static void writeShortLe(byte[] bytes, int offset, int value) {
        bytes[offset] = (byte) (value & 0xff);
        bytes[offset + 1] = (byte) ((value >>> 8) & 0xff);
    }

    private static void writeIntLe(byte[] bytes, int offset, int value) {
        bytes[offset] = (byte) (value & 0xff);
        bytes[offset + 1] = (byte) ((value >>> 8) & 0xff);
        bytes[offset + 2] = (byte) ((value >>> 16) & 0xff);
        bytes[offset + 3] = (byte) ((value >>> 24) & 0xff);
    }

    private record WavMetadata(
            int sampleRate,
            int channels,
            int bitsPerSample,
            int dataBytes) {
    }

    public record SynthesizedAudio(
            byte[] audioBytes,
            int sampleRate,
            int bitsPerSample,
            int durationMs,
            String transcript) {
    }
}
