package com.example.demo.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import com.example.demo.handler.BackupWebSocketHandler;

@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final BackupWebSocketHandler backupWebSocketHandler;

    // 注入自定义处理器
    public WebSocketConfig(BackupWebSocketHandler backupWebSocketHandler) {
        this.backupWebSocketHandler = backupWebSocketHandler;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        // 注册WebSocket端点，允许跨域
        registry.addHandler(backupWebSocketHandler, "/ws/backup")
                .setAllowedOrigins("*"); // 生产环境需指定具体域名
    }
}