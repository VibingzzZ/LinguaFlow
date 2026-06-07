package com.javaee.backend.service;

//import dev.langchain4j.model.chat.ChatLanguageModel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

@Slf4j
@Service
public class InterpretationService {

//    private final ChatLanguageModel fastModel;       // 快速翻译
//    private final ChatLanguageModel refineModel;     // 修正模型

    // 每个会话维护一个上下文窗口，存储最近 N 句话的原文+翻译
    private final Map<String, ContextWindow> sessionContexts = new ConcurrentHashMap<>();

    // 修正触发阈值：攒够 N 句新翻译才做一次回溯修正
    private static final int CORRECTION_WINDOW = 5;

//    public InterpretationService(ChatLanguageModel fastModel, ChatLanguageModel refineModel) {
//        this.fastModel = fastModel;
//        this.refineModel = refineModel;
//    }

    /**
     * 处理音频块（实际项目中这里先调 ASR，这里简化为直接收到文本）
     *
     * @param sessionId  会话ID
     * @param audioBase64 音频 base64（或识别后的文本）
     * @param sourceLang 源语言
     * @param targetLang 目标语言
     * @param callback   翻译结果回调 → Handler 拿到后发给前端
     */
    public void processAudioChunk(
            String sessionId,
            String audioBase64,
            String sourceLang,
            String targetLang,
            Consumer<Map<String, Object>> callback) {

        // TODO: 实际项目这里调 ASR（阿里云NLS / 讯飞）
        // 现在先把 audioBase64 当成已经识别好的文本
        String recognizedText = audioBase64;
        if (recognizedText == null || recognizedText.isBlank()) return;

        // 1. 快速翻译
        // TODO: 队友接入模型后，取消下方注释并删除 mockFastTranslate 调用
        // String translation = fastModel.generate(prompt);
        String translation = mockFastTranslate(recognizedText, sourceLang, targetLang);

        // 2. 保存到上下文窗口
        ContextWindow window = sessionContexts.computeIfAbsent(
                sessionId, k -> new ContextWindow()
        );
        int index = window.add(recognizedText, translation);

        // 3. 下发字幕
        callback.accept(Map.of(
                "type", "SUBTITLE",
                "index", index,
                "sourceText", recognizedText,
                "targetText", translation,
                "timestamp", System.currentTimeMillis()
        ));

        // 4. 攒够 CORRECTION_WINDOW 句，触发修正
        if (window.getPendingCount() >= CORRECTION_WINDOW) {
            checkAndCorrect(window, callback);
        }
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

        // TODO: 队友接入模型后，取消下方注释并删除 mockRefine 调用
        // String review = refineModel.generate(prompt);
        String review = mockRefine(reviewList);
        List<Map.Entry<Integer, String>> corrections = parseCorrections(review);

        for (Map.Entry<Integer, String> correction : corrections) {
            int idx = correction.getKey();
            String oldTranslation = window.getTranslation(idx);
            String newTranslation = correction.getValue();

            window.updateTranslation(idx, newTranslation);

            callback.accept(Map.of(
                    "type", "CORRECTION",
                    "index", idx,
                    "oldTranslation", oldTranslation,
                    "newTranslation", newTranslation,
                    "timestamp", System.currentTimeMillis()
            ));
        }

        window.markReviewed();  // 标记这批已审阅
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

    // ======================== TODO: 以下为临时占位方法，队友接入模型后删除 ========================

    /**
     * 快速翻译占位 — 队友接入 fastModel 后删除此方法
     */
    private String mockFastTranslate(String text, String sourceLang, String targetLang) {
        log.warn("[MOCK] fastModel 未接入，返回占位译文: {} → {}", sourceLang, targetLang);
        return "[待翻译] " + text;
    }

    /**
     * 上下文修正占位 — 队友接入 refineModel 后删除此方法
     */
    private String mockRefine(List<String> reviewList) {
        log.warn("[MOCK] refineModel 未接入，返回全 OK");
        return reviewList.stream().map(s -> "OK").collect(java.util.stream.Collectors.joining("\n"));
    }

    // ======================== 占位方法结束 ========================

    /** 会话断开时清理上下文 */
    public void cleanup(String sessionId) {
        sessionContexts.remove(sessionId);
    }

    // ======================== 内部类：上下文窗口 ========================

    private static class ContextWindow {
        private final List<Sentence> sentences = new ArrayList<>();
        private int reviewedUpTo = 0;  // 已经审阅到第几句

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