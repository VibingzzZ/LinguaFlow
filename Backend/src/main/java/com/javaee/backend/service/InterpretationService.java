package com.javaee.backend.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.javaee.backend.websocket.EventType;

import java.util.*;
import java.util.Base64;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * 同声传译服务 - 整合ASR + 翻译 + 上下文修正
 */
@Slf4j
@Service
public class InterpretationService {

    @Autowired
    private TranslationService translationService;

    @Autowired
    private AsrService asrService;

    // 每个会话维护一个上下文窗口，存储最近 N 句话的原文+翻译
    private final Map<String, ContextWindow> sessionContexts = new ConcurrentHashMap<>();

    // 会话语言配置
    private final Map<String, SessionConfig> sessionConfigs = new ConcurrentHashMap<>();

    // 修正触发阈值：攒够 N 句新翻译才做一次回溯修正
    private static final int CORRECTION_WINDOW = 5;

    /**
     * 启动同声传译会话（初始化ASR）
     * @param sessionId 会话ID
     * @param sourceLang 源语言 (en/zh/ja等)
     * @param targetLang 目标语言
     * @param callback 结果回调
     */
    public void startSession(String sessionId, String sourceLang, String targetLang,
                            Consumer<Map<String, Object>> callback) {
        // 保存会话配置
        sessionConfigs.put(sessionId, new SessionConfig(sourceLang, targetLang));

        // 初始化上下文窗口
        sessionContexts.put(sessionId, new ContextWindow());

        // 启动ASR识别
        boolean success = asrService.startRecognition(sessionId,
                new AsrService.RecognitionCallback() {
                    @Override
                    public void onResult(String text, boolean isFinal) {
                        handleAsrResult(sessionId, text, isFinal, callback);
                    }

                    @Override
                    public void onError(String errorMessage) {
                        log.error("ASR错误: sessionId={}, error={}", sessionId, errorMessage);
                        callback.accept(Map.of(
                                "type", "ERROR",
                                "code", 5001,
                                "message", "语音识别错误: " + errorMessage,
                                "timestamp", System.currentTimeMillis()
                        ));
                    }
                });

        if (success) {
            log.info("同传会话启动: sessionId={}, {}→{}", sessionId, sourceLang, targetLang);
            callback.accept(Map.of(
                    "type", EventType.CONNECTED.name(),
                    "sessionId", sessionId,
                    "sourceLang", sourceLang,
                    "targetLang", targetLang,
                    "message", "同传服务就绪",
                    "timestamp", System.currentTimeMillis()
            ));
        } else {
            callback.accept(Map.of(
                    "type", EventType.ERROR.name(),
                    "code", 5000,
                    "message", "启动ASR服务失败",
                    "timestamp", System.currentTimeMillis()
            ));
        }
    }

    /**
     * 处理音频块 - 发送到ASR进行识别
     *
     * @param sessionId   会话ID
     * @param audioBase64 Base64编码的音频数据
     * @param sourceLang  源语言
     * @param targetLang  目标语言
     * @param callback    翻译结果回调
     */
    public void processAudioChunk(
            String sessionId,
            String audioBase64,
            String sourceLang,
            String targetLang,
            Consumer<Map<String, Object>> callback) {

        if (audioBase64 == null || audioBase64.isBlank()) return;

        try {
            // 解码Base64音频数据
            byte[] audioData = Base64.getDecoder().decode(audioBase64);

            // 发送到ASR进行实时识别
            asrService.sendAudio(sessionId, audioData);

        } catch (Exception e) {
            log.error("处理音频块失败: sessionId={}", sessionId, e);
            callback.accept(Map.of(
                    "type", EventType.ERROR.name(),
                    "code", 5002,
                    "message", "音频处理异常: " + e.getMessage(),
                    "timestamp", System.currentTimeMillis()
            ));
        }
    }

    /**
     * 处理ASR识别结果并触发翻译
     */
    private void handleAsrResult(String sessionId, String recognizedText, boolean isFinal,
                                 Consumer<Map<String, Object>> callback) {
        if (recognizedText == null || recognizedText.isBlank()) return;

        SessionConfig config = sessionConfigs.get(sessionId);
        if (config == null) {
            log.warn("未找到会话配置: {}", sessionId);
            return;
        }

        // 获取或创建上下文窗口
        ContextWindow window = sessionContexts.computeIfAbsent(sessionId, k -> new ContextWindow());

        // 快速翻译（异步服务，低延迟）
        String translation = translationService.translateSync(
                recognizedText, config.sourceLang, config.targetLang);

        // 判断事件类型：中间结果 vs 最终句子
        String eventType = isFinal ? EventType.COMPLET.name() : EventType.SUBTITLE.name();

        int index = window.add(recognizedText, translation);

        // 下发字幕/终句
        Map<String, Object> result = new HashMap<>();
        result.put("type", eventType);
        result.put("index", index);
        result.put("sourceText", recognizedText);
        result.put("targetText", translation);
        result.put("isFinal", isFinal);
        result.put("timestamp", System.currentTimeMillis());

        callback.accept(result);

        log.debug("[{}] text={}, translation={}", eventType, recognizedText, translation);

        // 仅对最终句子进行上下文修正
        if (isFinal && window.getPendingCount() >= CORRECTION_WINDOW) {
            checkAndCorrect(window, callback);
        }
    }

    /**
     * 停止同传会话
     */
    public void stopSession(String sessionId) {
        // 停止ASR
        asrService.stopRecognition(sessionId);

        // 清理会话数据
        sessionContexts.remove(sessionId);
        sessionConfigs.remove(sessionId);

        log.info("同传会话停止: {}", sessionId);
    }

    /**
     * 上下文修正：把窗口内的句子重新审视一遍
     */
    private void checkAndCorrect(ContextWindow window, Consumer<Map<String, Object>> callback) {
        List<String> reviewList = window.getPendingForReview();

        String prompt = String.format(
                """
                你是一个翻译质量审查员。下面是一组连续的句子及其翻译，
                请结合上下文检查是否有翻译不准确的地方。
                如果某句翻译没问题，回复 "OK"。
                如果某句需要修正，回复格式： "修正第N句: <新翻译>"

                句子列表：
                %s
                """,
                String.join("\n", reviewList)
        );

        // TODO: 接入refineModel后替换mockRefine
        String review = mockRefine(reviewList);
        List<Map.Entry<Integer, String>> corrections = parseCorrections(review);

        for (Map.Entry<Integer, String> correction : corrections) {
            int idx = correction.getKey();
            String oldTranslation = window.getTranslation(idx);
            String newTranslation = correction.getValue();

            window.updateTranslation(idx, newTranslation);

            callback.accept(Map.of(
                    "type", EventType.CORRECTION.name(),
                    "index", idx,
                    "oldTranslation", oldTranslation,
                    "newTranslation", newTranslation,
                    "timestamp", System.currentTimeMillis()
            ));
        }

        window.markReviewed();
    }

    /**
     * 解析修正模型的输出
     */
    private List<Map.Entry<Integer, String>> parseCorrections(String review) {
        List<Map.Entry<Integer, String>> corrections = new ArrayList<>();
        for (String line : review.split("\n")) {
            line = line.trim();
            if (line.startsWith("修正第")) {
                try {
                    int idx = Integer.parseInt(line.substring(3, line.indexOf("句")));
                    String newText = line.substring(line.indexOf(":") + 1).trim();
                    corrections.add(new AbstractMap.SimpleEntry<>(idx, newText));
                } catch (Exception e) {
                    log.warn("解析修正行失败: {}", line);
                }
            }
        }
        return corrections;
    }

    /** 会话断开时清理上下文（兼容旧接口） */
    public void cleanup(String sessionId) {
        stopSession(sessionId);
    }

    // ======================== 占位方法 ========================

    /**
     * 上下文修正占位 — 接入 refineModel 后删除此方法
     */
    private String mockRefine(List<String> reviewList) {
        log.warn("[MOCK] refineModel 未接入，返回全 OK");
        return reviewList.stream().map(s -> "OK").collect(java.util.stream.Collectors.joining("\n"));
    }

    // ======================== 内部类 ========================

    /** 会话配置 */
    private record SessionConfig(String sourceLang, String targetLang) {}

    /** 上下文窗口 */
    private static class ContextWindow {
        private final List<Sentence> sentences = new ArrayList<>();
        private int reviewedUpTo = 0;

        record Sentence(int index, String source, String translation) {}

        int add(String source, String translation) {
            int idx = sentences.size();
            sentences.add(new Sentence(idx, source, translation));
            return idx;
        }

        int getPendingCount() {
            return sentences.size() - reviewedUpTo;
        }

        List<String> getPendingForReview() {
            List<String> list = new ArrayList<>();
            for (int i = reviewedUpTo; i < sentences.size(); i++) {
                Sentence s = sentences.get(i);
                list.add(String.format("第%d句: %s → %s", s.index, s.source, s.translation));
            }
            return list;
        }

        String getTranslation(int index) {
            return sentences.get(index).translation;
        }

        void updateTranslation(int index, String newTranslation) {
            Sentence old = sentences.get(index);
            sentences.set(index, new Sentence(index, old.source, newTranslation));
        }

        void markReviewed() {
            reviewedUpTo = sentences.size();
        }
    }
}
