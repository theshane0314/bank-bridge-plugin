package com.plaincandle.bankbridge.ws;

import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;

/**
 * Callback surface for {@link WSWebsocketServer}, so the server class stays a thin adapter over
 * Java-WebSocket and all of the logic lives in a testable, injectable manager.
 */
public interface WSHandler
{
	default void onOpen(WebSocket conn, ClientHandshake handshake) {}

	default void onClose(WebSocket conn, int code, String reason, boolean remote) {}

	default void onMessage(WebSocket conn, String message) {}

	default void onError(WebSocket conn, Exception ex) {}

	default void onStart() {}
}
