package com.javaee.backend.websocket;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.javaee.backend.service.InterpretationService;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketMessage;
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

    //所有活跃的客户端连接（sessionId->session）
    public InterpretationHandler(InterpretationService interpretationService) {
        this.interpretationService = interpretationService;
    }

    @Override
    public void afterConnectionEstablished(@NonNull WebSocketSession session) throws Exception {
        sessions.put(session.getId(), session);
        log.info("websocket连接建立：{}", session.getId());
        sendMessage(session, Map.of(
                "type", EventType.CONNECTED.name(),
                "sessionId", session.getId(),
                "message", "同传服务就绪"
        ));

    }


    /**
     * 收到消息 → 类似收到一个 TCP segment，解析 payload
     * @param session 当前会话
     * @param message 接收到的消息
     */
    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        try {
            JsonNode json = objectMapper.readTree(message.getPayload());
            String type = json.get("type").asText();

            if (EventType.AUDIO_CHUNK.name().equals(type)) {
                // 音频块 → 送入翻译流水线，结果通过回调异步发回
                String audioBase64 = json.get("data").asText();
                String sourceLang = json.get("sourceLang").asText();
                String targetLang = json.get("targetLang").asText();

                interpretationService.processAudioChunk(
                        session.getId(),
                        audioBase64,
                        sourceLang,
                        targetLang,
                        event -> sendMessage(session, event)  // 回调：翻译好了就发给前端
                );
            } else {
                log.warn("未知事件类型: {}", type);
            }
        } catch (Exception e) {
            log.error("消息处理异常", e);
            handleMessage(session, (WebSocketMessage<?>) Map.of(
                    "type", EventType.ERROR.name(),
                    "code", 500,
                    "message", e.getMessage()
            ));
        }
    }

    /**
     * 连接关闭 → 相当于 TCP 四次挥手
     * @param session 关闭的会话
     * @param status 关闭状态码
     */
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        sessions.remove(session.getId());
        interpretationService.cleanup(session.getId());  // 清理该会话的上下文
        log.info("WebSocket 连接关闭: {}, 状态码: {}", session.getId(), status.getCode());
    }


    /**
     * 传输错误 → 类似 TCP 收到 RST
     */
    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        log.error("传输错误: {}", session.getId(), exception);
        sessions.remove(session.getId());
    }

    private void sendMessage(WebSocketSession session, Object payload) {
        try {
            String json = objectMapper.writeValueAsString(payload);
            synchronized (session) {  // WebSocketSession 不是线程安全的，需要同步
                session.sendMessage(new TextMessage(json));
            }
        } catch (IOException e) {
            log.error("发送消息失败", e);
        }
    }
}
