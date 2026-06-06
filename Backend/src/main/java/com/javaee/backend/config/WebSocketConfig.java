package com.javaee.backend.config;

import com.javaee.backend.websocket.InterpretationHandler;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    @Autowired
    private InterpretationHandler interpretationHandler;

    public WebSocketConfig(InterpretationHandler interpretationHandler){
        this.interpretationHandler=interpretationHandler;
    }
    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler((WebSocketHandler) interpretationHandler, "/interpretation")
                .setAllowedOrigins("*");
    }
}
