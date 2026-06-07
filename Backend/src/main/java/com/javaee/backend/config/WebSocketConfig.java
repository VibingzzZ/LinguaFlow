package com.javaee.backend.config;

import com.javaee.backend.websocket.InterpretationHandler;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final InterpretationHandler interpretationHandler;

    public WebSocketConfig(InterpretationHandler interpretationHandler) {
        this.interpretationHandler = interpretationHandler;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(interpretationHandler, "/api/ws/interpretation")
                .setAllowedOriginPatterns("*");
    }
}
