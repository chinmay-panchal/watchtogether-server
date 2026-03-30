package com.watchtogether.model;

import org.springframework.web.socket.WebSocketSession;

import java.util.concurrent.CopyOnWriteArrayList;

public class Room {
    private final String roomId;
    private final CopyOnWriteArrayList<WebSocketSession> sessions = new CopyOnWriteArrayList<>();
    private final long createdAt;

    public Room(String roomId) {
        this.roomId = roomId;
        this.createdAt = System.currentTimeMillis();
    }

    public String getRoomId() {
        return roomId;
    }

    public boolean addSession(WebSocketSession session) {
        if (sessions.size() >= 2) {
            return false; // Room is full
        }
        sessions.add(session);
        return true;
    }

    public void removeSession(WebSocketSession session) {
        sessions.remove(session);
    }

    public CopyOnWriteArrayList<WebSocketSession> getSessions() {
        return sessions;
    }

    public boolean isFull() {
        return sessions.size() >= 2;
    }

    public boolean isEmpty() {
        return sessions.isEmpty();
    }

    public WebSocketSession getOtherSession(WebSocketSession session) {
        for (WebSocketSession s : sessions) {
            if (!s.getId().equals(session.getId())) {
                return s;
            }
        }
        return null;
    }

    public long getCreatedAt() {
        return createdAt;
    }
}