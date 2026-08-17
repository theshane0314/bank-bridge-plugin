package com.plaincandle.bankbridge.ws;

/**
 * Callback surface for {@link WSWebsocketServer}, so the server class stays a thin transport and all
 * of the policy (who is allowed to connect, what a request means) lives in a testable, injectable
 * manager.
 */
public interface WSHandler
{
	default void onOpen(WSConnection conn, Handshake handshake) {}

	default void onClose(WSConnection conn, int code, String reason, boolean remote) {}

	default void onMessage(WSConnection conn, String message) {}

	/** A null connection means the failure is the server's own, almost always a busy port. */
	default void onError(WSConnection conn, Exception ex) {}

	default void onStart() {}
}
