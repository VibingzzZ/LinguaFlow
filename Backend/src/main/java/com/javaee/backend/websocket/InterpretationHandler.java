package com.javaee.backend.websocket;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.javaee.backend.service.InterpretationService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
public class InterpretationHandler extends TextWebSocketHandler {

    @Autowired
    private InterpretationService interpretationService;

    @Autowired
    private ObjectMapper objectMapper;

    // 所有活跃的客户端连接 (sessionId -> session)
    private final Map<String, WebSocketSession> sessions = new ConcurrentHashMap<>();

    /**
     * 连接建立 → 启动同传会话
     */
    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        String sessionId = session.getId();
        sessions.put(sessionId, session);
        log.info("websocket连接建立：{}", sessionId);

        // 默认语言：英文→中文（前端可通过后续消息切换）
        interpretationService.startSession(
                sessionId,
                "en",
                "zh",
                event -> sendMessage(session, event)
        );
    }

    /**
     * 收到消息 → 解析 payload 并分发处理
     */
    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        try {
            JsonNode json = objectMapper.readTree(message.getPayload());
            String type = json.has("type") ? json.get("type").asText() : "";

            switch (type) {
                case "AUDIO_CHUNK" -> {
                    String data = json.has("data") ? json.get("data").asText() : "";
                    if (data == null || data.isEmpty()) return;

                    // 判断是音频还是文本
                    boolean isAudioData = isBase64Audio(data);

                    if (isAudioData) {
                        // 音频数据：解码后发送到ASR
                        byte[] audioBytes = Base64.getDecoder().decode(data);
                        log.debug("收到音频块: sessionId={}, size={} bytes", session.getId(), audioBytes.length);
                        interpretationService.processAudioChunk(
                                session.getId(),
                                audioBytes,
                                event -> sendMessage(session, event)
                        );
                    } else {
                        // 文本数据：直接翻译测试
                        String sourceLang = json.has("sourceLang") ? json.get("sourceLang").asText() : "en";
                        String targetLang = json.has("targetLang") ? json.get("targetLang").asText() : "zh";
                        interpretationService.processText(
                                session.getId(),
                                data,
                                sourceLang,
                                targetLang,
                                event -> sendMessage(session, event)
                        );
                    }
                }
                case "STOP_AUDIO" -> {
                    // 前端停止录音，通知ASR停止识别并返回最终结果
                    log.info("收到停止音频信号: {}", session.getId());
                    interpretationService.stopSession(session.getId());
                }
                default ->
                        log.warn("未知事件类型: {}", type);
            }
        } catch (Exception e) {
            log.error("消息处理异常", e);
            try {
                sendMessage(session, Map.of(
                        "type", EventType.ERROR.name(),
                        "code", 500,
                        "message", e.getMessage()
                ));
            } catch (Exception ignored) {}
        }
    }

    /**
     * 连接关闭 → 清理会话
     */
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        String sessionId = session.getId();
        sessions.remove(sessionId);
        interpretationService.cleanup(sessionId);
        log.info("WebSocket 连接关闭: {}, 状态码: {}", sessionId, status.getCode());
    }

    /**
     * 传输错误
     */
    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        log.error("传输错误: {}", session.getId(), exception);
        sessions.remove(session.getId());
    }

    /**
     * 发送消息给客户端
     */
    private void sendMessage(WebSocketSession session, Object payload) {
        // 检查会话是否仍然打开
        if (session == null || !session.isOpen()) {
            log.debug("WebSocket会话已关闭，跳过发送: {}", payload);
            return;
        }
        try {
            String json = objectMapper.writeValueAsString(payload);
            synchronized (session) {  // WebSocketSession 不是线程安全的，需要同步
                session.sendMessage(new TextMessage(json));
            }
        } catch (IOException e) {
            // 静默处理已关闭会话的异常
            if (e.getMessage() != null && e.getMessage().contains("WebSocket session has been closed")) {
                log.debug("WebSocket已关闭，无法发送消息");
            } else {
                log.error("发送消息失败", e);
            }
        }
    }

    /**
     * 判断是否为音频 Base64 数据（而非纯文本）
     */
    private boolean isBase64Audio(String data) {
        try {
            byte[] decoded = Base64.getDecoder().decode(data);
            // 音频数据通常包含大量非打印字符，纯文本解码后应该是可读字符串
            return !isReadableString(decoded);
        } catch (Exception e) {
            return false;
        }
    }

    private boolean isReadableString(byte[] bytes) {
        for (byte b : bytes) {
            if (b < 0x20 && b != 0x09 && b != 0x0A && b != 0x0D) {
                return false;
            }
        }
        return true;
    }
}