package com.javaee.backend.service;

import com.javaee.backend.websocket.EventType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * 同声传译服务 - 整合ASR + 翻译 + 上下文修正
 */
@Slf4j
@Service
public class InterpretationService {

    @Autowired
    private AsrService asrService;

    @Autowired
    private TranslationService translationService;

    // 每个会话维护一个上下文窗口，存储最近 N 句话的原文+翻译
    private final Map<String, ContextWindow> sessionContexts = new ConcurrentHashMap<>();

    // 修正触发阈值：攒够 N 句新翻译才做一次回溯修正
    private static final int CORRECTION_WINDOW = 5;

    // ASR 是否可用
    private final Map<String, Boolean> asrAvailable = new ConcurrentHashMap<>();

    /**
     * 启动同传会话
     */
    public void startSession(String sessionId, String sourceLang, String targetLang, Consumer<Map<String, Object>> callback) {
        log.info("启动同传会话: sessionId={}, {}→{}", sessionId, sourceLang, targetLang);

        // 初始化上下文窗口
        sessionContexts.put(sessionId, new ContextWindow(sourceLang, targetLang));
        asrAvailable.put(sessionId, false);  // 默认不可用

        try {
            // 尝试启动ASR识别（异步，等onReady回调后才标记为可用）
            asrService.startRecognition(sessionId, new AsrService.RecognitionCallback() {
                @Override
                public void onReady() {
                    // ASR WebSocket连接成功，标记可用
                    asrAvailable.put(sessionId, true);
                    log.info("ASR就绪(WebSocket已连): {}", sessionId);
                }

                @Override
                public void onResult(String text, boolean isFinal) {
                    handleAsrResult(sessionId, text, isFinal, callback);
                }

                @Override
                public void onError(String errorMessage) {
                    log.warn("ASR不可用: sessionId={}, error={}", sessionId, errorMessage);
                    asrAvailable.put(sessionId, false);
                }
            });
            log.info("ASR启动请求已发送，等待就绪回调: {}", sessionId);
        } catch (Exception e) {
            log.warn("ASR启动失败，降级为文本模式: {}", e.getMessage());
            asrAvailable.put(sessionId, false);
        }

        // 无论ASR是否成功，都发送 CONNECTED
        String mode = Boolean.TRUE.equals(asrAvailable.get(sessionId)) ? "语音+文本模式" : "文本模式（ASR不可用）";
        callback.accept(Map.of(
                "type", EventType.CONNECTED.name(),
                "sessionId", sessionId,
                "sourceLang", sourceLang,
                "targetLang", targetLang,
                "message", "同传会话已建立 - " + mode,
                "timestamp", System.currentTimeMillis()
        ));
        log.info("同传会话启动: sessionId={}, {}→{}, 模式={}", sessionId, sourceLang, targetLang, mode);
    }

    /**
     * 处理音频块 - 发送到ASR（如果可用）
     */
    public void processAudioChunk(String sessionId, byte[] audioData, Consumer<Map<String, Object>> callback) {
        if (!Boolean.TRUE.equals(asrAvailable.get(sessionId))) {
            return;
        }
        try {
            asrService.sendAudio(sessionId, audioData);
        } catch (Exception e) {
            log.warn("ASR发送失败，标记为不可用: {}", e.getMessage());
            asrAvailable.put(sessionId, false);
            // 通知前端ASR降级
            try {
                callback.accept(Map.of(
                        "type", EventType.ERROR.name(),
                        "code", 5001,
                        "message", "ASR连接已断开，降级为文本模式",
                        "timestamp", System.currentTimeMillis()
                ));
            } catch (Exception ignored) {}
        }
    }

    /**
     * 处理文本输入（绕过ASR直接测试翻译）
     */
    public void processText(String sessionId, String text, String sourceLang, String targetLang,
                           Consumer<Map<String, Object>> callback) {
        if (text == null || text.isBlank()) return;
        handleRecognizedText(sessionId, text, false, callback);
    }

    /**
     * ASR结果回调处理
     */
    private void handleAsrResult(String sessionId, String recognizedText, boolean isFinal,
                                Consumer<Map<String, Object>> callback) {
        handleRecognizedText(sessionId, recognizedText, isFinal, callback);
    }

    /**
     * 统一的识别结果处理：翻译 → 上下文 → 下发字幕
     */
    private void handleRecognizedText(String sessionId, String recognizedText, boolean isFinal,
                                     Consumer<Map<String, Object>> callback) {
        ContextWindow window = sessionContexts.get(sessionId);
        if (window == null) return;

        String sourceLang = window.getSourceLang();
        String targetLang = window.getTargetLang();

        // 异步翻译
        translationService.translateAsync(recognizedText, sourceLang, targetLang, translation -> {
            try {
                int index = window.add(recognizedText, translation);

                String eventType = isFinal ? EventType.COMPLET.name() : EventType.SUBTITLE.name();
                callback.accept(new HashMap<>(Map.of(
                        "type", eventType,
                        "index", index,
                        "sourceText", recognizedText,
                        "targetText", translation,
                        "isFinal", isFinal,
                        "timestamp", System.currentTimeMillis()
                )));

                if (isFinal && translation != null && !translation.isBlank()
                        && !translation.startsWith("[")) {
                    callback.accept(new HashMap<>(Map.of(
                            "type", EventType.TTS_SPEAK.name(),
                            "text", translation,
                            "language", targetLang,
                            "timestamp", System.currentTimeMillis()
                    )));
                }

                // 攒够句子数触发上下文修正
                if (window.getPendingCount() >= CORRECTION_WINDOW) {
                    checkAndCorrect(window, callback);
                }
            } catch (Exception e) {
                log.error("发送翻译结果失败: {}", e.getMessage());
            }
        });
    }

    /**
     * 上下文修正：把窗口内的句子重新审视一遍
     */
    private void checkAndCorrect(ContextWindow window, Consumer<Map<String, Object>> callback) {
        List<String> reviewList = window.getPendingForReview();
        String combinedSource = reviewList.stream().collect(java.util.stream.Collectors.joining("\n"));

        String prompt = String.format(
                """
                你是一个翻译质量审查员。下面是一组连续的句子及其翻译，
                请结合上下文检查是否有翻译不准确的地方。
                如果某句翻译没问题，回复 "OK"。
                如果某句需要修正，回复格式： "修正第N句: <新翻译>"

                句子列表：
                %s
                """,
                combinedSource
        );

        translationService.translateAsync(prompt, "zh", "zh", review -> {
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
        });
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

    /**
     * 停止同传会话（先停止ASR让它返回最终结果，再清理资源）
     */
    public void stopSession(String sessionId) {
        // 先停止ASR，让它在回调中把最终结果发出去
        try {
            asrService.stopRecognition(sessionId);
            log.info("停止ASR识别会话: {}", sessionId);
        } catch (Exception e) {
            log.warn("停止ASR识别会话异常: {}", e.getMessage());
        }

        // 延迟清理：等ASR回调完成后再移除上下文
        new Thread(() -> {
            try { Thread.sleep(2000); } catch (InterruptedException ignored) {}
            sessionContexts.remove(sessionId);
            asrAvailable.remove(sessionId);
            log.info("同传会话资源已清理: {}", sessionId);
        }).start();
    }

    /** 会话断开时清理上下文 */
    public void cleanup(String sessionId) {
        stopSession(sessionId);
    }

    // ======================== 内部类：上下文窗口 ========================

    private static class ContextWindow {
        private final List<Sentence> sentences = new ArrayList<>();
        private int reviewedUpTo = 0;  // 已经审阅到第几句
        private final String sourceLang;
        private final String targetLang;

        ContextWindow(String sourceLang, String targetLang) {
            this.sourceLang = sourceLang;
            this.targetLang = targetLang;
        }

        String getSourceLang() { return sourceLang; }
        String getTargetLang() { return targetLang; }

        record Sentence(int index, String source, String translation) {}

        int add(String source, String translation) {
            int idx = sentences.size();
            sentences.add(new Sentence(idx, source, translation));
            return idx;
        }

        /** 获取还未被审阅的句子数量 */
        int getPendingCount() {
            return sentences.size() - reviewedUpTo;
        }

        /** 获取待审阅的句子列表 */
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