package com.plaincandle.bankbridge.ws;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A minimal WebSocket server built on {@link ServerSocket}, with no third party dependency.
 * <p>
 * Binds to the loopback interface only. Nothing on the network can reach this, by construction:
 * an explicit 127.0.0.1 bind rather than a wildcard bind plus a firewall rule.
 */
public class WSWebsocketServer
{
	private static final int BACKLOG = 8;

	private final InetSocketAddress address;
	private final WSHandler handler;
	private final Set<WSConnection> connections = ConcurrentHashMap.newKeySet();
	private final AtomicInteger connectionCounter = new AtomicInteger();

	private volatile ServerSocket serverSocket;
	private volatile boolean running;
	private boolean daemon = false;

	public WSWebsocketServer(int port, WSHandler handler)
	{
		this.address = new InetSocketAddress("127.0.0.1", port);
		this.handler = handler;
	}

	public void setDaemon(boolean daemon)
	{
		this.daemon = daemon;
	}

	public InetSocketAddress getAddress()
	{
		return address;
	}

	public int getPort()
	{
		return address.getPort();
	}

	/**
	 * Returns immediately. The bind happens on the accept thread, so a busy port surfaces as
	 * {@code onError(null, ex)} rather than as an exception on the caller: that asynchronous
	 * failure is what drives the port fallback across the 37767-37776 range.
	 */
	public void start()
	{
		running = true;
		Thread t = new Thread(this::acceptLoop, "bank-bridge-accept-" + getPort());
		t.setDaemon(daemon);
		t.start();
	}

	/**
	 * Closes the listener and every live connection.
	 * <p>
	 * Returns without waiting on the accept thread. Closing the server socket releases the port
	 * immediately and unblocks {@code accept()}, so the thread winds down on its own; not waiting
	 * also keeps this safe to call from the accept thread itself, which happens when
	 * {@code onError} drives the port fallback.
	 */
	public void stop()
	{
		running = false;

		ServerSocket ss = this.serverSocket;
		if (ss != null)
		{
			try
			{
				ss.close();
			}
			catch (IOException ignored)
			{
				// Already closed, or never opened.
			}
		}

		for (WSConnection conn : connections)
		{
			conn.closeQuietly(WSConnection.CLOSE_NORMAL, "server stopping", false);
		}
		connections.clear();
	}

	/** Sends to every open connection. A failing peer is skipped, never fatal to the others. */
	public void broadcast(String text)
	{
		for (WSConnection conn : connections)
		{
			if (conn.isOpen())
			{
				conn.send(text);
			}
		}
	}

	private void acceptLoop()
	{
		ServerSocket ss;
		try
		{
			ss = new ServerSocket();
			// Must stay false. On Windows SO_REUSEADDR lets a second bind succeed on a port that is
			// already listening, which would silently defeat the whole port fallback: we would
			// "bind" on top of WikiSync instead of stepping to the next port.
			ss.setReuseAddress(false);
			ss.bind(address, BACKLOG);
		}
		catch (IOException e)
		{
			handler.onError(null, e);
			return;
		}

		this.serverSocket = ss;
		handler.onStart();

		while (running)
		{
			Socket socket;
			try
			{
				socket = ss.accept();
			}
			catch (IOException e)
			{
				if (running)
				{
					// The listener itself is broken. Report it and let the handler rebind rather
					// than spinning on a socket that will keep failing.
					handler.onError(null, e);
				}
				return;
			}

			try
			{
				socket.setTcpNoDelay(true);
				WSConnection conn = new WSConnection(socket, handler);
				connections.add(conn);

				Thread t = new Thread(() -> {
					try
					{
						conn.serve();
					}
					finally
					{
						connections.remove(conn);
					}
				}, "bank-bridge-conn-" + connectionCounter.incrementAndGet());
				t.setDaemon(true);
				t.start();
			}
			catch (IOException e)
			{
				// One peer failed to set up. That is not a server fault, so it must NOT be reported
				// as onError(null, ...): that means "the listener is broken, rebind" and would move
				// the whole server to another port over a single bad connection.
				try
				{
					socket.close();
				}
				catch (IOException ignored)
				{
					// Nothing left to do with it.
				}
			}
		}
	}
}
