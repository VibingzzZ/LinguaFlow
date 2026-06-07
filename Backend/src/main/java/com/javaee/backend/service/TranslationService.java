package com.javaee.backend.service;

import java.util.function.Consumer;

/**
 * 翻译服务接口
 */
public interface TranslationService {

    void translateAsync(String text, String sourceLang, String targetLang,
                        Consumer<String> callback);

    String translateSync(String text, String sourceLang, String targetLang);

    void clearCache();

    int getCacheSize();
}