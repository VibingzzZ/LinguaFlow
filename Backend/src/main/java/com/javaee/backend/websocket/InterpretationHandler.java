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
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
public class InterpretationHandler extends TextWebSocketHandler {

    @Autowired
    private InterpretationService interpretationService;

    @Autowired
    private ObjectMapper objectMapper;

    private final Map<String, WebSocketSession> sessions = new ConcurrentHashMap<>();

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        sessions.put(session.getId(), session);
        log.info("websocket连接建立：{}", session.getId());

        interpretationService.startSession(
                session.getId(),
                "en",
                "zh",
                event -> sendMessage(session, event)
        );
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        try {
            JsonNode json = objectMapper.readTree(message.getPayload());
            String type = json.get("type").asText();

            if (EventType.AUDIO_CHUNK.name().equals(type)) {
                String audioBase64 = json.get("data").asText();
                String sourceLang = json.has("sourceLang") ? json.get("sourceLang").asText() : "en";
                String targetLang = json.has("targetLang") ? json.get("targetLang").asText() : "zh";

                interpretationService.processAudioChunk(
                        session.getId(),
                        audioBase64,
                        sourceLang,
                        targetLang,
                        event -> sendMessage(session, event)
                );
            } else {
                log.warn("未知事件类型: {}", type);
            }
        } catch (Exception e) {
            log.error("消息处理异常", e);
            sendMessage(session, Map.of(
                    "type", EventType.ERROR.name(),
                    "code", 500,
                    "message", e.getMessage()
            ));
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        sessions.remove(session.getId());
        interpretationService.stopSession(session.getId());
        log.info("WebSocket 连接关闭: {}, 状态码: {}", session.getId(), status.getCode());
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        log.error("传输错误: {}", session.getId(), exception);
        sessions.remove(session.getId());
    }

    private void sendMessage(WebSocketSession session, Object payload) {
        try {
            String json = objectMapper.writeValueAsString(payload);
            synchronized (session) {
                session.sendMessage(new TextMessage(json));
            }
        } catch (IOException e) {
            log.error("发送消息失败", e);
        }
    }
}
