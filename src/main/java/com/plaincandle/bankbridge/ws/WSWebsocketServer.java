package com.plaincandle.bankbridge.ws;

import java.net.InetSocketAddress;
import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;

/**
 * Binds to the loopback interface only. Nothing on the network can reach this, by construction —
 * an explicit 127.0.0.1 bind rather than a wildcard bind plus a firewall rule.
 */
public class WSWebsocketServer extends WebSocketServer
{
	private final WSHandler handler;

	public WSWebsocketServer(int port, WSHandler handler)
	{
		super(new InetSocketAddress("127.0.0.1", port), 1);
		this.setDaemon(true);
		this.handler = handler;
	}

	@Override
	public void onOpen(WebSocket conn, ClientHandshake handshake)
	{
		this.handler.onOpen(conn, handshake);
	}

	@Override
	public void onClose(WebSocket conn, int code, String reason, boolean remote)
	{
		this.handler.onClose(conn, code, reason, remote);
	}

	@Override
	public void onMessage(WebSocket conn, String message)
	{
		this.handler.onMessage(conn, message);
	}

	@Override
	public void onError(WebSocket conn, Exception ex)
	{
		this.handler.onError(conn, ex);
	}

	@Override
	public void onStart()
	{
		this.handler.onStart();
	}
}
