package com.javaee.backend.config;

import com.javaee.backend.websocket.InterpretationHandler;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean;
import org.springframework.context.annotation.Bean;

@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    @Autowired
    private InterpretationHandler interpretationHandler;

    public WebSocketConfig(InterpretationHandler interpretationHandler){
        this.interpretationHandler=interpretationHandler;
    }

    // 关键：增大 WebSocket 消息缓冲区（默认只有 8KB，音频数据会超限导致断连）
    @Bean
    public ServletServerContainerFactoryBean createWebSocketContainer() {
        ServletServerContainerFactoryBean container = new ServletServerContainerFactoryBean();
        container.setMaxTextMessageBufferSize(512 * 1024);   // 文本消息: 512KB
        container.setMaxBinaryMessageBufferSize(1024 * 1024); // 二进制消息: 1MB
        container.setMaxSessionIdleTimeout(30 * 60 * 1000L);  // 空闲超时: 30分钟
        return container;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler((WebSocketHandler) interpretationHandler, "/api/ws/interpretation")
                .setAllowedOrigins("*");
    }
}
