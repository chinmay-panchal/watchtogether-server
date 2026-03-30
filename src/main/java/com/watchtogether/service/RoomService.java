package com.watchtogether.service;

import com.watchtogether.model.Room;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.WebSocketSession;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class RoomService {
    private static final Logger log = LoggerFactory.getLogger(RoomService.class);
    private static final long ROOM_TIMEOUT_MS = 4 * 60 * 60 * 1000L; // 4 hours

    // roomId -> Room
    private final Map<String, Room> rooms = new ConcurrentHashMap<>();
    // sessionId -> roomId
    private final Map<String, String> sessionRoomMap = new ConcurrentHashMap<>();

    public Room createRoom(String roomId, WebSocketSession session) {
        if (rooms.containsKey(roomId)) {
            log.warn("Room {} already exists", roomId);
            return null;
        }
        Room room = new Room(roomId);
        room.addSession(session);
        rooms.put(roomId, room);
        sessionRoomMap.put(session.getId(), roomId);
        log.info("Room {} created by session {}", roomId, session.getId());
        return room;
    }

    public Room joinRoom(String roomId, WebSocketSession session) {
        Room room = rooms.get(roomId);
        if (room == null) {
            log.warn("Room {} not found", roomId);
            return null;
        }
        if (room.isFull()) {
            log.warn("Room {} is full", roomId);
            return null;
        }
        room.addSession(session);
        sessionRoomMap.put(session.getId(), roomId);
        log.info("Session {} joined room {}", session.getId(), roomId);
        return room;
    }

    public Room getRoomBySession(WebSocketSession session) {
        String roomId = sessionRoomMap.get(session.getId());
        if (roomId == null) return null;
        return rooms.get(roomId);
    }

    public void removeSession(WebSocketSession session) {
        String roomId = sessionRoomMap.remove(session.getId());
        if (roomId != null) {
            Room room = rooms.get(roomId);
            if (room != null) {
                room.removeSession(session);
                if (room.isEmpty()) {
                    rooms.remove(roomId);
                    log.info("Room {} deleted (empty)", roomId);
                }
            }
        }
    }

    public Room getRoom(String roomId) {
        return rooms.get(roomId);
    }

    // Clean up stale rooms every 30 minutes
    @Scheduled(fixedRate = 30 * 60 * 1000)
    public void cleanupStaleRooms() {
        long now = System.currentTimeMillis();
        rooms.entrySet().removeIf(entry -> {
            boolean stale = (now - entry.getValue().getCreatedAt()) > ROOM_TIMEOUT_MS;
            if (stale) log.info("Cleaning up stale room: {}", entry.getKey());
            return stale;
        });
    }
}