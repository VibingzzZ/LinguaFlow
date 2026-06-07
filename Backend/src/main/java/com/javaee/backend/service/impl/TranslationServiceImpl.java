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
    // 增加到 15 秒，因为长文本翻译需要更多时间
    private static final int TIMEOUT_MS = 15000;

    // 速率限制：两次请求最小间隔（毫秒），防止触发 API RPM 限制
    // 实时同传场景下，增加到 3 秒间隔，避免频繁触发限流
    private static final long MIN_REQUEST_INTERVAL_MS = 3000;

    // 上次请求时间戳（synchronized 保护）
    private long lastRequestTime = 0;
    private final Object rateLimitLock = new Object();

    // 连续限流错误计数器
    private int consecutiveRateLimitErrors = 0;
    private final Object rateLimitCounterLock = new Object();

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
                """
                你是一个专业的同声传译员。请将以下%s翻译为%s。
                
                要求：
                1. 只输出翻译结果，不要任何解释或额外内容
                2. 保持口语化表达，适合语音朗读
                3. 保留专有名词原文（人名、地名、品牌名等）
                4. 如果是疑问句/感叹句，保留语气
                5. 短句优先，避免冗长
                
                原文：%s
                """,
                srcName, tgtName, text
        );

        try {
            String result = chatModel.chat(prompt);
            // 成功时重置限流计数器
            synchronized (rateLimitCounterLock) {
                consecutiveRateLimitErrors = 0;
            }
            return result.trim();
        } catch (Exception e) {
            // 检查是否为429限流错误
            boolean isRateLimit = e.getClass().getSimpleName().contains("RateLimit")
                    || (e.getMessage() != null && e.getMessage().contains("429"));
            
            if (isRateLimit) {
                synchronized (rateLimitCounterLock) {
                    consecutiveRateLimitErrors++;
                }
                
                // 指数退避：第1次等5秒，第2次等15秒，第3次等30秒
                int retryCount = Math.min(consecutiveRateLimitErrors, 3);
                long backoffSeconds = retryCount == 1 ? 5 : retryCount == 2 ? 15 : 30;
                
                log.warn("触发RPM限制(连续{}次)，指数退避等待{}秒...", consecutiveRateLimitErrors, backoffSeconds);
                try {
                    Thread.sleep(backoffSeconds * 1000);
                    String result = chatModel.chat(prompt);
                    synchronized (rateLimitCounterLock) {
                        consecutiveRateLimitErrors = 0;
                    }
                    return result.trim();
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