package com.plaincandle.bankbridge;

import com.google.common.collect.ImmutableSet;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.google.gson.Gson;
import com.plaincandle.bankbridge.messages.Request;
import com.plaincandle.bankbridge.messages.response.BankUpdated;
import com.plaincandle.bankbridge.messages.response.ErrorResponse;
import com.plaincandle.bankbridge.messages.response.GetBank;
import com.plaincandle.bankbridge.messages.response.Hello;
import com.plaincandle.bankbridge.ws.Handshake;
import com.plaincandle.bankbridge.ws.WSConnection;
import com.plaincandle.bankbridge.ws.WSHandler;
import com.plaincandle.bankbridge.ws.WSWebsocketServer;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.HashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;

/**
 * Owns the local WebSocket server: its lifecycle, its port, who is allowed to talk to it, and how
 * requests are answered.
 * <p>
 * Note what this class does <em>not</em> have: any reference to {@code Client} or
 * {@code ClientThread}. Requests are answered purely out of {@link SnapshotStore}, so no socket
 * traffic can ever reach the game client, let alone block it.
 */
@Slf4j
@Singleton
public class WebSocketManager implements WSHandler
{
	/**
	 * The same range WikiSync uses. Sharing the convention means a page can scan one port range and
	 * find whichever of these plugins the user has installed.
	 */
	private static final int PORT_MIN = 37767;
	private static final int PORT_MAX = 37776;

	/**
	 * The security boundary. A WebSocket is not subject to the same-origin policy, so without this
	 * check, ANY website you happened to have open could read your bank. Browsers set Origin on
	 * every WebSocket handshake and it cannot be forged by page JavaScript.
	 */
	private static final Set<String> ALLOWED_ORIGIN_HOSTS = ImmutableSet.of(
		"osrs.plaincandle.dev",
		"localhost",
		"127.0.0.1"
	);

	private final AtomicBoolean serverActive = new AtomicBoolean(false);

	private final Gson gson;
	private final SnapshotStore store;
	private final BankBridgeConfig config;

	private int nextPort;
	private WSWebsocketServer server;

	private static final ExecutorService executorService = Executors.newSingleThreadExecutor(
		new ThreadFactoryBuilder().setDaemon(true).setNameFormat("bank-bridge-ws-%d").build());

	@Inject
	WebSocketManager(Gson gson, SnapshotStore store, BankBridgeConfig config)
	{
		this.gson = gson;
		this.store = store;
		this.config = config;
	}

	public void startUp()
	{
		this.nextPort = PORT_MIN;
		// In case we are resuming from a bad state, drop anything still bound.
		stopServer();
		ensureActive();
	}

	public void shutDown()
	{
		log.debug("Shutting down Bank Bridge socket. Active = {}", serverActive.getPlain());
		stopServer();
	}

	/**
	 * Starts a server if one is not already running. Designed to be called on a schedule, so it is a
	 * no-op on most invocations, and is what recovers the socket if every port was busy at start-up
	 * and one later frees up.
	 */
	public void ensureActive()
	{
		if (!serverActive.compareAndExchange(false, true))
		{
			this.server = new WSWebsocketServer(this.nextPort++, this);
			this.server.start();
			log.debug("Bank Bridge socket attempting to bind: {}", this.server.getAddress());
			if (this.nextPort > PORT_MAX)
			{
				this.nextPort = PORT_MIN;
			}
		}
	}

	/** Tells every connected page that the bank changed. Never carries the payload itself. */
	public void broadcastBankUpdated(long capturedAt)
	{
		if (!serverActive.get())
		{
			return;
		}
		final String json = gson.toJson(new BankUpdated(capturedAt));
		executorService.submit(() -> {
			WSWebsocketServer s = this.server;
			if (s != null)
			{
				s.broadcast(json);
			}
		});
	}

	@Override
	public void onOpen(WSConnection conn, Handshake handshake)
	{
		String requestPath = conn.getResourceDescriptor();
		String origin = handshake.getFieldValue("origin");
		log.debug("Bank Bridge connection attempt. path={}, origin={}", requestPath, origin);

		if (!Objects.equals(requestPath, "/"))
		{
			log.debug("Rejecting unknown request path: {}", requestPath);
			conn.close();
			return;
		}

		if (!isOriginAllowed(origin))
		{
			log.debug("Rejecting unauthorised origin: {}", origin);
			conn.close();
			return;
		}

		send(conn, new Hello(
			BankBridgePlugin.VERSION,
			SnapshotStore.SCHEMA_VERSION,
			store.getUsername(),
			store.isBankAvailable(),
			store.getCapturedAt()
		));
	}

	@Override
	public void onMessage(WSConnection conn, String message)
	{
		Request request;
		try
		{
			request = gson.fromJson(message, Request.class);
		}
		catch (RuntimeException e)
		{
			log.debug("Unparseable request", e);
			send(conn, new ErrorResponse(0, "Malformed request"));
			return;
		}

		if (request == null || request.get_wsType() == null)
		{
			send(conn, new ErrorResponse(request == null ? 0 : request.getSequenceId(), "Unknown request type"));
			return;
		}

		switch (request.get_wsType())
		{
			case GetBank:
				// Answered straight out of the cached snapshot: no client thread, no blocking.
				send(conn, new GetBank(request.getSequenceId(), store.snapshot()));
				break;
			default:
				send(conn, new ErrorResponse(request.getSequenceId(), "Unsupported request type"));
				break;
		}
	}

	@Override
	public void onError(WSConnection conn, Exception ex)
	{
		log.debug("Bank Bridge socket error conn=[{}]", conn == null ? null : conn.getLocalSocketAddress(), ex);

		// A null connection means the failure is the server's, not a peer's, and almost always a port
		// that is already in use.
		if (conn == null)
		{
			stopServer();
			// Try the next port once per port. If we have wrapped all the way back to PORT_MIN,
			// stop and wait for the scheduled ensureActive rather than spinning.
			if (this.nextPort != PORT_MIN)
			{
				ensureActive();
			}
		}
	}

	@Override
	public void onStart()
	{
		log.debug("Bank Bridge listening on 127.0.0.1:{}", server.getPort());
	}

	/** @return the bound port, or -1 if the socket is not currently listening. */
	public int getPort()
	{
		WSWebsocketServer s = this.server;
		return s == null || !serverActive.get() ? -1 : s.getPort();
	}

	private void send(WSConnection conn, Object message)
	{
		final String json = gson.toJson(message);
		executorService.submit(() -> {
			try
			{
				conn.send(json);
			}
			catch (RuntimeException e)
			{
				log.debug("Failed to send", e);
			}
		});
	}

	private boolean isOriginAllowed(String origin)
	{
		if (origin == null || origin.trim().isEmpty())
		{
			// Browsers always send Origin on a WebSocket handshake; something that omits it is not
			// a page, so there is no user intent behind it. An absent header reaches here as "",
			// and it is rejected explicitly rather than by leaning on URI("") parsing to a null
			// host further down.
			return false;
		}

		try
		{
			String host = new URI(origin).getHost();
			if (host == null)
			{
				return false;
			}
			host = host.toLowerCase(Locale.ROOT);
			return allowedHosts().contains(host);
		}
		catch (URISyntaxException e)
		{
			log.debug("Could not parse origin: {}", origin);
			return false;
		}
	}

	private Set<String> allowedHosts()
	{
		String extra = config.extraOrigins();
		if (extra == null || extra.trim().isEmpty())
		{
			return ALLOWED_ORIGIN_HOSTS;
		}

		Set<String> hosts = new HashSet<>(ALLOWED_ORIGIN_HOSTS);
		for (String s : extra.split(","))
		{
			String host = s.trim().toLowerCase(Locale.ROOT);
			if (!host.isEmpty())
			{
				hosts.add(host);
			}
		}
		return hosts;
	}

	private void stopServer()
	{
		try
		{
			if (this.server != null)
			{
				try
				{
					this.server.stop();
				}
				finally
				{
					this.server = null;
				}
			}
		}
		finally
		{
			this.serverActive.set(false);
		}
	}
}
