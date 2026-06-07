package com.javaee.backend.service.impl;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.javaee.backend.service.TranslationService;

import dev.langchain4j.model.chat.ChatModel;

import java.util.Map;
import java.util.concurrent.*;
import java.util.function.Consumer;

/**
 * 翻译服务实现 - 基于LangChain4j大模型
 * 优化策略：
 * 1. 异步非阻塞调用
 * 2. 本地缓存常用翻译
 * 3. 超时控制
 */
@Slf4j
@Service
public class TranslationServiceImpl implements TranslationService {

    @Autowired
    private ChatModel chatModel;

    // 翻译结果缓存（原文 -> 译文）
    private final Map<String, String> translationCache = new ConcurrentHashMap<>();

    // 异步线程池
    private final ExecutorService executor = Executors.newFixedThreadPool(
            Runtime.getRuntime().availableProcessors(),
            new ThreadFactory() {
                private int count = 0;
                @Override
                public Thread newThread(Runnable r) {
                    Thread t = new Thread(r);
                    t.setName("translation-pool-" + (count++));
                    t.setDaemon(true);
                    return t;
                }
            }
    );

    // 超时时间（毫秒）
    private static final int TIMEOUT_MS = 3000;

    // 速率限制：两次请求最小间隔（毫秒），防止触发 API RPM 限制
    // 现在只在终句时调用翻译，所以1秒间隔足够
    private static final long MIN_REQUEST_INTERVAL_MS = 1000;

    // 上次请求时间戳（synchronized 保护）
    private long lastRequestTime = 0;
    private final Object rateLimitLock = new Object();

    // 语言名称映射
    private static final Map<String, String> LANG_NAMES = Map.of(
            "en", "English", "zh", "中文", "ja", "日本語", "ko", "한국어"
    );

    @Override
    public void translateAsync(String text, String sourceLang, String targetLang,
                               Consumer<String> callback) {
        if (text == null || text.trim().isEmpty()) {
            callback.accept("");
            return;
        }

        String cacheKey = sourceLang + ":" + targetLang + ":" + text;
        String cached = translationCache.get(cacheKey);
        if (cached != null) {
            log.debug("缓存命中: {}", cacheKey);
            callback.accept(cached);
            return;
        }

        CompletableFuture.supplyAsync(() -> {
            try {
                String result = doTranslate(text, sourceLang, targetLang);
                if (text.length() < 50) {
                    translationCache.put(cacheKey, result);
                }
                return result;
            } catch (Exception e) {
                log.error("翻译失败: {}", text, e);
                return "[翻译错误] " + text;
            }
        }, executor).orTimeout(TIMEOUT_MS, TimeUnit.MILLISECONDS)
          .exceptionally(ex -> {
              log.warn("翻译超时，使用原文: {}", text);
              return "[处理中] " + text;
          })
          .thenAccept(callback);
    }

    @Override
    public String translateSync(String text, String sourceLang, String targetLang) {
        if (text == null || text.trim().isEmpty()) {
            return "";
        }

        String cacheKey = sourceLang + ":" + targetLang + ":" + text;
        String cached = translationCache.get(cacheKey);
        if (cached != null) {
            return cached;
        }

        try {
            Future<String> future = executor.submit(() ->
                doTranslate(text, sourceLang, targetLang)
            );

            String result = future.get(TIMEOUT_MS, TimeUnit.MILLISECONDS);

            if (text.length() < 50) {
                translationCache.put(cacheKey, result);
            }

            return result;
        } catch (TimeoutException e) {
            log.warn("翻译超时: {}", text);
            return "[处理中] " + text;
        } catch (Exception e) {
            log.error("翻译异常: {}", text, e);
            return "[错误] " + text;
        }
    }

    /**
     * 调用LangChain4j大模型进行翻译（带速率限制）
     */
    private String doTranslate(String text, String sourceLang, String targetLang) {
        if (chatModel == null) {
            log.warn("ChatLanguageModel未注入，返回占位译文");
            return "[AI未配置] " + text;
        }

        // 速率限制：确保两次请求之间有足够间隔（synchronized 保证线程安全）
        synchronized (rateLimitLock) {
            long now = System.currentTimeMillis();
            long waitMs = lastRequestTime + MIN_REQUEST_INTERVAL_MS - now;
            if (waitMs > 0) {
                try {
                    Thread.sleep(waitMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return "[限流中断] " + text;
                }
            }
            lastRequestTime = System.currentTimeMillis();
        }

        String srcName = LANG_NAMES.getOrDefault(sourceLang, sourceLang);
        String tgtName = LANG_NAMES.getOrDefault(targetLang, targetLang);

        String prompt = String.format(
                "请将以下%s翻译为%s，只输出翻译结果，不要任何解释：\n%s",
                srcName, tgtName, text
        );

        try {
            return chatModel.chat(prompt);
        } catch (Exception e) {
            // 检查是否为429限流错误
            boolean isRateLimit = e.getClass().getSimpleName().contains("RateLimit")
                    || (e.getMessage() != null && e.getMessage().contains("429"));
            
            if (isRateLimit) {
                log.warn("触发RPM限制，等待5秒后重试...");
                try {
                    Thread.sleep(5000);
                    return chatModel.chat(prompt);
                } catch (Exception retryEx) {
                    log.error("重试后仍然失败: {}", retryEx.getMessage());
                    return "[限流错误] " + text;
                }
            }
            log.error("调用大模型失败: {}", e.getMessage());
            return "[模型错误] " + text;
        }
    }

    @Override
    public void clearCache() {
        translationCache.clear();
        log.info("翻译缓存已清理");
    }

    @Override
    public int getCacheSize() {
        return translationCache.size();
    }
}