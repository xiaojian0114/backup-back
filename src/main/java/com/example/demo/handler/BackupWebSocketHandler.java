package com.example.demo.handler;

import com.example.demo.entity.BackupLog;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import java.io.IOException;
import java.util.concurrent.CopyOnWriteArraySet;

@Component
public class BackupWebSocketHandler extends TextWebSocketHandler {

    // 存储所有活跃会话
    private final CopyOnWriteArraySet<WebSocketSession> sessions = new CopyOnWriteArraySet<>();
    private final ObjectMapper objectMapper;

    public BackupWebSocketHandler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    // 连接建立时
    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        sessions.add(session);
        System.out.println("新客户端连接：" + session.getId() + "，当前连接数: " + sessions.size());
    }

    // 连接关闭时
    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        sessions.remove(session);
        System.out.println("客户端断开连接：" + session.getId() + "，剩余连接数: " + sessions.size());
    }

    /**
     * 发送断点信息到所有客户端
     */
    public void sendBreakpoint(BackupLog log) throws IOException {
        if (sessions.isEmpty()) {
            System.out.println("无活跃客户端，跳过断点推送");
            return;
        }

        // 序列化断点信息为JSON
        String jsonMessage = objectMapper.writeValueAsString(log);
        TextMessage message = new TextMessage(jsonMessage);

        // 遍历所有会话发送消息
        for (WebSocketSession session : sessions) {
            if (session.isOpen()) {
                session.sendMessage(message);
                System.out.println("已推送断点信息到客户端 " + session.getId() + "：" + log.getFilename());
            }
        }
    }
}