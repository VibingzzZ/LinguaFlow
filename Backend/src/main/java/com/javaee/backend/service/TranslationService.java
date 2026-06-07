package com.javaee.backend.service;

import java.util.function.Consumer;

/**
 * 翻译服务接口
 */
public interface TranslationService {

    /**
     * 异步翻译 - 立即返回，不阻塞
     * @param text 待翻译文本
     * @param sourceLang 源语言
     * @param targetLang 目标语言
     * @param callback 翻译完成回调
     */
    void translateAsync(String text, String sourceLang, String targetLang,
                        Consumer<String> callback);

    /**
     * 同步翻译（带超时控制）
     * @param text 待翻译文本
     * @param sourceLang 源语言
     * @param targetLang 目标语言
     * @return 翻译结果
     */
    String translateSync(String text, String sourceLang, String targetLang);

    /**
     * 清理缓存
     */
    void clearCache();

    /**
     * 获取缓存大小
     */
    int getCacheSize();
}
