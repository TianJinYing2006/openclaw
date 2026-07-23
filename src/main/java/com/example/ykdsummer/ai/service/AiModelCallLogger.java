package com.example.ykdsummer.ai.service;

import com.example.ykdsummer.ai.model.AiFile;
import com.example.ykdsummer.ai.model.AiImage;
import com.example.ykdsummer.ai.model.ConversationMessage;
import org.slf4j.Logger;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.stream.Collectors;

final class AiModelCallLogger {

    private AiModelCallLogger() {
    }

    static void textRequest(
            Logger logger,
            String model,
            String systemPrompt,
            List<ConversationMessage> history,
            String prompt
    ) {
        logger.info(
                "大模型请求：协议=Chat Completions，模型={}，系统提示词={}，历史消息={}，当前问题={}",
                model,
                safeText(systemPrompt),
                historySummary(history),
                safeText(prompt)
        );
    }

    static void responsesRequest(
            Logger logger,
            String model,
            String reasoningEffort,
            String systemPrompt,
            List<ConversationMessage> history,
            String prompt,
            List<AiImage> images,
            List<AiFile> files
    ) {
        logger.info(
                "大模型请求：协议=Responses，模型={}，推理强度={}，系统说明={}，历史消息={}，当前问题={}，图片={}，文件={}",
                model,
                reasoningEffort,
                safeText(systemPrompt),
                historySummary(history),
                safeText(prompt),
                imageSummary(images),
                fileSummary(files)
        );
    }

    static void imageRequest(Logger logger, String userId, String model, String size, String quality, String prompt) {
        logger.info(
                "生图请求：用户={}，模型={}，尺寸={}，质量={}，提示词={}",
                userId,
                model,
                size,
                quality,
                safeText(prompt)
        );
    }

    static void response(Logger logger, String protocol, String model, String text) {
        logger.info(
                "大模型响应：协议={}，模型={}，回复内容={}",
                protocol,
                model,
                safeText(text)
        );
    }

    static void imageResponse(Logger logger, String userId, String model, byte[] bytes) {
        logger.info(
                "生图响应：用户={}，模型={}，字节数={}，SHA-256={}",
                userId,
                model,
                bytes.length,
                sha256(bytes)
        );
    }

    private static String historySummary(List<ConversationMessage> history) {
        return (history == null ? List.<ConversationMessage>of() : history).stream()
                .map(message -> "{角色=" + (message.role() == ConversationMessage.Role.USER ? "用户" : "助手")
                        + "，内容=" + safeText(message.text()) + "}")
                .collect(Collectors.joining("，", "[", "]"));
    }

    private static String imageSummary(List<AiImage> images) {
        return (images == null ? List.<AiImage>of() : images).stream()
                .map(image -> "{媒体类型=" + image.mediaType()
                        + "，字节数=" + image.bytes().length
                        + "，SHA-256=" + sha256(image.bytes())
                        + "，细节=" + image.detail() + "}")
                .collect(Collectors.joining("，", "[", "]"));
    }

    private static String fileSummary(List<AiFile> files) {
        return (files == null ? List.<AiFile>of() : files).stream()
                .map(file -> "{文件名=" + file.fileName()
                        + "，媒体类型=" + file.mediaType()
                        + "，字节数=" + file.bytes().length
                        + "，SHA-256=" + sha256(file.bytes()) + "}")
                .collect(Collectors.joining("，", "[", "]"));
    }

    private static String safeText(String value) {
        return value == null ? "" : value;
    }

    private static String sha256(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            return java.util.HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("JDK does not provide SHA-256", impossible);
        }
    }
}