package com.javaee.backend.service.impl;

import com.alibaba.nls.client.AccessToken;
import com.alibaba.nls.client.protocol.InputFormatEnum;
import com.alibaba.nls.client.protocol.NlsClient;
import com.alibaba.nls.client.protocol.SampleRateEnum;
import com.alibaba.nls.client.protocol.SpeechReqProtocol;
import com.alibaba.nls.client.protocol.asr.SpeechTranscriber;
import com.alibaba.nls.client.protocol.asr.SpeechTranscriberListener;
import com.alibaba.nls.client.protocol.asr.SpeechTranscriberResponse;
import com.javaee.backend.config.AsrConfig;
import com.javaee.backend.service.AsrService;
import com.javaee.backend.service.AsrService.RecognitionCallback;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 阿里云实时语音识别服务实现
 * 支持流式音频输入，实时返回识别结果
 */
@Slf4j
@Service
public class AsrServiceImpl implements AsrService {

    @Autowired
    private AsrConfig asrConfig;

    private NlsClient nlsClient;
    private String currentToken;

    // 会话ID -> 转录器实例
    private final Map<String, SpeechTranscriber> transcriberMap = new ConcurrentHashMap<>();

    @PostConstruct
    public void init() {
        // 打印实际使用的配置（排查 UID 不匹配问题）
        log.info("=== ASR 配置 ===");
        log.info("  AccessKeyId : {}...{}", asrConfig.getAccessKeyId().substring(0, 8), asrConfig.getAccessKeyId().substring(asrConfig.getAccessKeyId().length() - 4));
        log.info("  Secret      : {}...{}", asrConfig.getAccessKeySecret().substring(0, 4), asrConfig.getAccessKeySecret().substring(asrConfig.getAccessKeySecret().length() - 4));
        log.info("  AppKey      : {}", asrConfig.getAppKey());
        log.info("  URL         : {}", asrConfig.getUrl());
        log.info("===============");

        try {
            refreshToken();
            log.info("阿里云ASR服务初始化成功");
        } catch (Exception e) {
            log.error("阿里云ASR服务初始化失败", e);
        }
    }

    @PreDestroy
    public void destroy() {
        // 关闭所有活跃的转录器
        transcriberMap.forEach((sessionId, transcriber) -> {
            try {
                transcriber.stop();
                transcriber.close();
            } catch (Exception e) {
                log.warn("关闭转录器失败: {}", sessionId, e);
            }
        });
        transcriberMap.clear();

        if (nlsClient != null) {
            nlsClient.shutdown();
            log.info("阿里云ASR服务已关闭");
        }
    }

    /**
     * 刷新访问令牌（Token有效期约24小时）
     */
    private synchronized void refreshToken() throws IOException {
        AccessToken accessToken = new AccessToken(
                asrConfig.getAccessKeyId(),
                asrConfig.getAccessKeySecret()
        );
        accessToken.apply();
        this.currentToken = accessToken.getToken();

        if (nlsClient != null) {
            nlsClient.shutdown();
        }
        this.nlsClient = new NlsClient(asrConfig.getUrl(), currentToken);
        log.info("Token刷新成功，过期时间: {}，连接地址: {}", accessToken.getExpireTime(), asrConfig.getUrl());
    }

    /**
     * 开始实时语音识别会话
     * @param sessionId 会话ID
     *param onResult 识别结果回调 (text, isFinal)
     * @return 是否启动成功
     */
    @Override
    public boolean startRecognition(String sessionId, RecognitionCallback callback) {
        try {
            // 检查token是否需要刷新（简化处理：每次创建新实例时检查）
            if (currentToken == null || nlsClient == null) {
                refreshToken();
            }

            SpeechTranscriber transcriber = new SpeechTranscriber(nlsClient, createListener(callback));
            transcriber.setAppKey(asrConfig.getAppKey());

            // 设置音频格式
            if ("pcm".equalsIgnoreCase(asrConfig.getFormat())) {
                transcriber.setFormat(InputFormatEnum.PCM);
            } else if ("wav".equalsIgnoreCase(asrConfig.getFormat())) {
                transcriber.setFormat(InputFormatEnum.WAV);
            } else if ("opus".equalsIgnoreCase(asrConfig.getFormat())) {
                transcriber.setFormat(InputFormatEnum.OPUS);
            }

            // 设置采样率
            if (asrConfig.getSampleRate() == 16000) {
                transcriber.setSampleRate(SampleRateEnum.SAMPLE_RATE_16K);
            } else if (asrConfig.getSampleRate() == 8000) {
                transcriber.setSampleRate(SampleRateEnum.SAMPLE_RATE_8K);
            }

            // 启用中间结果（实时返回，低延迟）
            transcriber.setEnableIntermediateResult(true);
            // 启用标点符号
            transcriber.setEnablePunctuation(true);
            // 启用ITN（数字转换）
            transcriber.setEnableITN(true);
            
            // 设置静音检测参数（关键！）
            // 静音超过 800ms 自动断句，触发 onSentenceEnd
            transcriber.addCustomedParam("max_sentence_silence", 800);
            // 最长语音断句间隔（毫秒），超过此时间强制断句
            transcriber.addCustomedParam("max_start_silence", 5000);

            // 启动识别
            log.info("启动ASR识别会话: sessionId={}, format={}, sampleRate={}", 
                    sessionId, asrConfig.getFormat(), asrConfig.getSampleRate());
            transcriber.start();

            transcriberMap.put(sessionId, transcriber);
            log.info("ASR识别会话已创建: {}", sessionId);
            return true;
        } catch (Exception e) {
            log.error("启动ASR识别失败: sessionId={}, error={}", sessionId, e.getMessage(), e);
            callback.onError(e.getMessage());
            return false;
        }
    }

    /**
     * 发送音频数据
     * @param sessionId 会话ID
     * @param audioData 音频字节数据（PCM/WAV格式）
     */
    @Override
    public void sendAudio(String sessionId, byte[] audioData) {
        SpeechTranscriber transcriber = transcriberMap.get(sessionId);
        if (transcriber == null) {
            log.warn("ASR转录器不存在(可能未启动): {}", sessionId);
            return;
        }
        try {
            // 检查状态：只有 STARTED 状态才能发送数据
            SpeechReqProtocol.State state = transcriber.getState();
            if ("STATE_CLOSED".equals(state) || "STATE_FINISHED".equals(state)) {
                log.warn("ASR已处于{}状态，无法发送数据: {}", state, sessionId);
                return;
            }
            if (audioData != null && audioData.length > 0) {
                transcriber.send(audioData, audioData.length);
            }
        } catch (Exception e) {
            String msg = e.getMessage();
            if (msg != null && msg.contains("STATE_CLOSED")) {
                log.warn("ASR会话已关闭，停止发送: {}", sessionId);
            } else {
                log.error("发送音频数据异常: {}", sessionId, e);
            }
        }
    }

    /**
     * 停止识别会话
     * @param sessionId 会话ID
     * @throws Throwable 
     */
    @Override
    public void stopRecognition(String sessionId) {
        SpeechTranscriber transcriber = transcriberMap.remove(sessionId);
        if (transcriber != null) {
            try {
                // 检查状态，避免对已关闭的会话调用stop
                SpeechReqProtocol.State state = transcriber.getState();
                if ("STATE_CLOSED".equals(state) || "STATE_FINISHED".equals(state)) {
                    log.info("ASR会话已处于{}状态，跳过stop: {}", state, sessionId);
                } else {
                    transcriber.stop();
                    log.info("ASR识别会话停止: {}", sessionId);
                }
            } catch (Exception e) {
                if (e.getMessage() != null && e.getMessage().contains("current state is")) {
                    log.debug("ASR会话已处于终态，忽略: {}", e.getMessage());
                } else {
                    log.warn("停止ASR识别会话异常: {}", sessionId, e);
                }
            } finally {
                try { transcriber.close(); } catch (Exception ignored) {}
            }
        }
    }

    /**
     * 创建监听器
     */
    private SpeechTranscriberListener createListener(RecognitionCallback callback) {
        return new SpeechTranscriberListener() {

            @Override
            public void onTranscriptionResultChange(SpeechTranscriberResponse response) {
                // 中间识别结果（实时、不稳定的中间状态）
                if (response.getStatus() == 20000000) {
                    String text = response.getTransSentenceText();
                    if (text != null && !text.isEmpty()) {
                        callback.onResult(text, false);  // 中间结果
                        log.debug("[ASR-中间] session={}, text={}", response.getTaskId(), text);
                    }
                }
            }

            @Override
            public void onSentenceEnd(SpeechTranscriberResponse response) {
                // 一句话结束（最终结果）
                if (response.getStatus() == 20000000) {
                    String text = response.getTransSentenceText();
                    if (text != null && !text.isEmpty()) {
                        Double confidence = response.getConfidence();
                        callback.onResult(text, true);  // 最终结果
                        log.info("[ASR-终句] index={}, text={}, confidence={}",
                                response.getTransSentenceIndex(), text, confidence);
                    }
                }
            }

            @Override
            public void onTranscriberStart(SpeechTranscriberResponse response) {
                log.info("[ASR-开始] task_id={}, status={}", response.getTaskId(), response.getStatus());
                callback.onReady();  // 通知调用方：ASR已就绪，可以发送音频
            }

            @Override
            public void onTranscriptionComplete(SpeechTranscriberResponse response) {
                log.info("[ASR-完成] task_id={}, status={}", response.getTaskId(), response.getStatus());
            }

            @Override
            public void onFail(SpeechTranscriberResponse response) {
                log.error("[ASR-失败] task_id={}, status={}, message={}",
                        response.getTaskId(),
                        response.getStatus(),
                        response.getStatusText());
                callback.onError(response.getStatusText());
            }

            @Override
            public void onSentenceBegin(SpeechTranscriberResponse response) {
                log.debug("[ASR-句子开始] task_id={}", response.getTaskId());
            }
        };
    }
}