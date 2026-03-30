package com.watchtogether.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.watchtogether.model.Room;
import com.watchtogether.service.RoomService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;

@Component
public class SignalingHandler extends TextWebSocketHandler {
    private static final Logger log = LoggerFactory.getLogger(SignalingHandler.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private final RoomService roomService;

    public SignalingHandler(RoomService roomService) {
        this.roomService = roomService;
    }

    @Override
    public void handleTextMessage(WebSocketSession session, TextMessage message) {
        try {
            JsonNode json = mapper.readTree(message.getPayload());
            String type = json.get("type").asText();
            String roomId = json.has("roomId") ? json.get("roomId").asText() : null;

            switch (type) {
                case "create"    -> handleCreate(session, roomId);
                case "join"      -> handleJoin(session, roomId);
                case "offer"     -> handleOffer(session, json);
                case "answer"    -> handleAnswer(session, json);
                case "candidate" -> handleCandidate(session, json);
                // Simple relay messages — just forward the type to the other peer.
                // screen_start : sharer started screen share (viewer should expect a screen stream next)
                // camera_off   : peer turned off camera (other side clears PiP)
                // screen_off   : sharer stopped screen share (viewer clears main view)
                case "screen_start", "camera_off", "screen_off" -> handleRelay(session, type);
                default -> sendError(session, "Unknown message type: " + type);
            }
        } catch (Exception e) {
            log.error("Error handling message from {}: {}", session.getId(), e.getMessage());
            sendError(session, "Internal error");
        }
    }

    private void handleCreate(WebSocketSession session, String roomId) throws IOException {
        if (roomId == null || roomId.isBlank()) { sendError(session, "roomId is required"); return; }
        Room room = roomService.createRoom(roomId, session);
        if (room == null) { sendError(session, "Room already exists or could not be created"); return; }
        sendMessage(session, buildMessage("created", "roomId", roomId));
        log.debug("Room created: {}", roomId);
    }

    private void handleJoin(WebSocketSession session, String roomId) throws IOException {
        if (roomId == null || roomId.isBlank()) { sendError(session, "roomId is required"); return; }
        Room room = roomService.joinRoom(roomId, session);
        if (room == null) { sendError(session, "Room not found or is full"); return; }
        sendMessage(session, buildMessage("joined", "roomId", roomId));
        WebSocketSession initiator = room.getOtherSession(session);
        if (initiator != null && initiator.isOpen()) {
            sendMessage(initiator, buildMessage("peer_joined", "roomId", roomId));
        }
        log.debug("Peer joined room: {}", roomId);
    }

    private void handleOffer(WebSocketSession session, JsonNode json) throws IOException {
        Room room = roomService.getRoomBySession(session);
        if (room == null) { sendError(session, "Not in a room"); return; }
        WebSocketSession peer = room.getOtherSession(session);
        if (peer == null || !peer.isOpen()) { sendError(session, "Peer not connected"); return; }
        ObjectNode msg = mapper.createObjectNode();
        msg.put("type", "offer");
        msg.put("sdp", json.get("sdp").asText());
        sendMessage(peer, msg);
        log.debug("Offer relayed in room {}", room.getRoomId());
    }

    private void handleAnswer(WebSocketSession session, JsonNode json) throws IOException {
        Room room = roomService.getRoomBySession(session);
        if (room == null) { sendError(session, "Not in a room"); return; }
        WebSocketSession peer = room.getOtherSession(session);
        if (peer == null || !peer.isOpen()) { sendError(session, "Peer not connected"); return; }
        ObjectNode msg = mapper.createObjectNode();
        msg.put("type", "answer");
        msg.put("sdp", json.get("sdp").asText());
        sendMessage(peer, msg);
        log.debug("Answer relayed in room {}", room.getRoomId());
    }

    private void handleCandidate(WebSocketSession session, JsonNode json) throws IOException {
        Room room = roomService.getRoomBySession(session);
        if (room == null) return;
        WebSocketSession peer = room.getOtherSession(session);
        if (peer == null || !peer.isOpen()) return;
        ObjectNode msg = mapper.createObjectNode();
        msg.put("type", "candidate");
        msg.set("candidate", json.get("candidate"));
        sendMessage(peer, msg);
    }

    /** Forwards a simple {type} message to the other peer in the room. */
    private void handleRelay(WebSocketSession session, String type) throws IOException {
        Room room = roomService.getRoomBySession(session);
        if (room == null) return;
        WebSocketSession peer = room.getOtherSession(session);
        if (peer == null || !peer.isOpen()) return;
        ObjectNode msg = mapper.createObjectNode();
        msg.put("type", type);
        sendMessage(peer, msg);
        log.debug("{} relayed in room {}", type, room.getRoomId());
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        Room room = roomService.getRoomBySession(session);
        if (room != null) {
            WebSocketSession peer = room.getOtherSession(session);
            roomService.removeSession(session);
            if (peer != null && peer.isOpen()) {
                try {
                    sendMessage(peer, buildMessage("peer_left", "message", "Peer disconnected"));
                } catch (IOException e) {
                    log.warn("Could not notify peer of disconnect");
                }
            }
        }
        log.info("Session {} disconnected: {}", session.getId(), status);
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable ex) {
        log.error("Transport error for session {}: {}", session.getId(), ex.getMessage());
        roomService.removeSession(session);
    }

    private void sendMessage(WebSocketSession session, ObjectNode message) throws IOException {
        if (session.isOpen()) {
            synchronized (session) {
                session.sendMessage(new TextMessage(mapper.writeValueAsString(message)));
            }
        }
    }

    private ObjectNode buildMessage(String type, String key, String value) {
        ObjectNode node = mapper.createObjectNode();
        node.put("type", type);
        node.put(key, value);
        return node;
    }

    private void sendError(WebSocketSession session, String message) {
        try {
            ObjectNode err = mapper.createObjectNode();
            err.put("type", "error");
            err.put("message", message);
            sendMessage(session, err);
        } catch (IOException e) {
            log.error("Failed to send error to session {}", session.getId());
        }
    }
}