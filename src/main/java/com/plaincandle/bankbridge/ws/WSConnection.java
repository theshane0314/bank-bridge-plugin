package com.plaincandle.bankbridge.ws;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;
import java.net.SocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * One accepted WebSocket connection: the RFC 6455 handshake, frame decoding, frame encoding and
 * close semantics, over a plain {@link Socket}.
 * <p>
 * This is written against the JDK alone rather than a WebSocket library. That is a deliberate
 * trade: the protocol surface we actually need is small (text frames, ping, close, all inbound
 * traffic from a browser on loopback), and having no third party dependency is what lets the plugin
 * build as {@code build=standard} on the Plugin Hub, which is what gets it reviewed.
 * <p>
 * The rules that matter and are easy to get wrong, all from RFC 6455 section 5:
 * <ul>
 *   <li>every client to server frame is masked, and a server MUST reject an unmasked one</li>
 *   <li>every server to client frame is NOT masked</li>
 *   <li>control frames are never fragmented and never exceed 125 bytes of payload</li>
 *   <li>a message can arrive split across a data frame plus continuation frames</li>
 * </ul>
 */
public final class WSConnection
{
	/** RFC 6455 section 1.3. Concatenated with the client key to derive the accept token. */
	private static final String MAGIC = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";

	private static final int OP_CONTINUATION = 0x0;
	private static final int OP_TEXT = 0x1;
	private static final int OP_BINARY = 0x2;
	private static final int OP_CLOSE = 0x8;
	private static final int OP_PING = 0x9;
	private static final int OP_PONG = 0xA;

	public static final int CLOSE_NORMAL = 1000;
	public static final int CLOSE_PROTOCOL_ERROR = 1002;
	public static final int CLOSE_UNSUPPORTED_DATA = 1003;
	public static final int CLOSE_TOO_LARGE = 1009;

	/**
	 * Requests are a few dozen bytes of JSON. This is the ceiling on what an unauthenticated peer
	 * can make us buffer, so it is set to the smallest number that cannot plausibly be hit.
	 */
	private static final int MAX_MESSAGE_BYTES = 64 * 1024;

	/** A peer that connects and then says nothing must not hold a thread forever. */
	private static final int HANDSHAKE_TIMEOUT_MS = 10_000;

	private final Socket socket;
	private final DataInputStream in;
	private final OutputStream out;
	private final WSHandler handler;

	/** Serialises frame writes. Sends arrive from the manager's executor and from broadcast. */
	private final Object writeLock = new Object();

	private final AtomicBoolean closeReported = new AtomicBoolean(false);
	private volatile boolean open = false;
	private volatile Handshake handshake;

	WSConnection(Socket socket, WSHandler handler) throws IOException
	{
		this.socket = socket;
		this.handler = handler;
		this.in = new DataInputStream(new java.io.BufferedInputStream(socket.getInputStream(), 8192));
		this.out = socket.getOutputStream();
	}

	public boolean isOpen()
	{
		return open;
	}

	public String getResourceDescriptor()
	{
		Handshake h = this.handshake;
		return h == null ? null : h.getResourceDescriptor();
	}

	public SocketAddress getLocalSocketAddress()
	{
		return socket.getLocalSocketAddress();
	}

	/**
	 * Sends a text message as a single unfragmented frame. Silently does nothing on a closed
	 * connection, which is what lets the manager fire and forget from its executor.
	 */
	public void send(String text)
	{
		if (!open)
		{
			return;
		}

		byte[] payload = text.getBytes(StandardCharsets.UTF_8);
		try
		{
			synchronized (writeLock)
			{
				writeFrameHeader(OP_TEXT, payload.length);
				out.write(payload);
				out.flush();
			}
		}
		catch (IOException e)
		{
			closeQuietly(CLOSE_NORMAL, "write failed", false);
		}
	}

	public void close()
	{
		closeQuietly(CLOSE_NORMAL, "", false);
	}

	public void close(int code, String reason)
	{
		closeQuietly(code, reason, false);
	}

	// ------------------------------------------------------------------------------- server driver

	/**
	 * Runs the connection to completion on the calling thread: handshake, then the read loop.
	 * Returns once the connection is finished.
	 */
	void serve()
	{
		try
		{
			socket.setSoTimeout(HANDSHAKE_TIMEOUT_MS);
			Handshake h = Handshake.read(in);

			String failure = validate(h);
			if (failure != null)
			{
				refuse(failure);
				return;
			}

			this.handshake = h;
			accept(h.getFieldValue("Sec-WebSocket-Key"));
			this.open = true;

			// The handshake is bounded; the session that follows is not, and a browser tab can sit
			// idle for hours between requests.
			socket.setSoTimeout(0);

			handler.onOpen(this, h);

			readLoop();
		}
		catch (IOException e)
		{
			// A peer hanging up is the normal way a connection ends, not an error worth surfacing.
			closeQuietly(CLOSE_NORMAL, "", true);
		}
		catch (RuntimeException e)
		{
			handler.onError(this, e);
			closeQuietly(CLOSE_PROTOCOL_ERROR, "internal error", false);
		}
		finally
		{
			closeQuietly(CLOSE_NORMAL, "", false);
		}
	}

	/** @return null when the request is a valid RFC 6455 upgrade, otherwise why it is not */
	private String validate(Handshake h)
	{
		if (!"GET".equals(h.getMethod()))
		{
			return "Method Not Allowed";
		}

		if (!h.getFieldValue("Upgrade").toLowerCase(Locale.ROOT).contains("websocket"))
		{
			return "Expected Upgrade: websocket";
		}

		if (!h.getFieldValue("Connection").toLowerCase(Locale.ROOT).contains("upgrade"))
		{
			return "Expected Connection: Upgrade";
		}

		if (!"13".equals(h.getFieldValue("Sec-WebSocket-Version")))
		{
			return "Unsupported Sec-WebSocket-Version";
		}

		if (h.getFieldValue("Sec-WebSocket-Key").isEmpty())
		{
			return "Missing Sec-WebSocket-Key";
		}

		return null;
	}

	private void accept(String key) throws IOException
	{
		String token = Base64.getEncoder().encodeToString(sha1(key + MAGIC));
		String response = "HTTP/1.1 101 Switching Protocols\r\n"
			+ "Upgrade: websocket\r\n"
			+ "Connection: Upgrade\r\n"
			+ "Sec-WebSocket-Accept: " + token + "\r\n"
			+ "\r\n";
		out.write(response.getBytes(StandardCharsets.ISO_8859_1));
		out.flush();
	}

	/** Turns down a request that never became a WebSocket, with an HTTP response and no frames. */
	private void refuse(String reason) throws IOException
	{
		String body = reason + "\n";
		String response = "HTTP/1.1 400 Bad Request\r\n"
			+ "Content-Type: text/plain; charset=utf-8\r\n"
			+ "Content-Length: " + body.getBytes(StandardCharsets.UTF_8).length + "\r\n"
			+ "Connection: close\r\n"
			+ "\r\n"
			+ body;
		out.write(response.getBytes(StandardCharsets.UTF_8));
		out.flush();
	}

	private static byte[] sha1(String s)
	{
		try
		{
			return MessageDigest.getInstance("SHA-1").digest(s.getBytes(StandardCharsets.UTF_8));
		}
		catch (NoSuchAlgorithmException e)
		{
			// SHA-1 is required of every JVM by the MessageDigest spec.
			throw new IllegalStateException("SHA-1 unavailable", e);
		}
	}

	// ------------------------------------------------------------------------------------ decoding

	private void readLoop() throws IOException
	{
		ByteArrayOutputStream message = null;
		int messageOpcode = -1;

		while (open)
		{
			int b0 = in.readUnsignedByte();
			boolean fin = (b0 & 0x80) != 0;
			if ((b0 & 0x70) != 0)
			{
				// RSV1-3 are only meaningful with a negotiated extension, and we negotiate none.
				closeQuietly(CLOSE_PROTOCOL_ERROR, "reserved bits set", false);
				return;
			}
			int opcode = b0 & 0x0F;

			int b1 = in.readUnsignedByte();
			boolean masked = (b1 & 0x80) != 0;
			long length = b1 & 0x7F;

			if (length == 126)
			{
				length = in.readUnsignedShort();
			}
			else if (length == 127)
			{
				length = in.readLong();
				if (length < 0)
				{
					closeQuietly(CLOSE_TOO_LARGE, "payload length overflow", false);
					return;
				}
			}

			if (!masked)
			{
				// Not a nicety: an unmasked client frame is how cache poisoning attacks against
				// intermediaries work, so the RFC requires the server to fail the connection.
				closeQuietly(CLOSE_PROTOCOL_ERROR, "client frames must be masked", false);
				return;
			}

			if (length > MAX_MESSAGE_BYTES)
			{
				closeQuietly(CLOSE_TOO_LARGE, "frame too large", false);
				return;
			}

			byte[] mask = new byte[4];
			in.readFully(mask);

			byte[] payload = new byte[(int) length];
			in.readFully(payload);
			for (int i = 0; i < payload.length; i++)
			{
				payload[i] ^= mask[i & 3];
			}

			if (opcode >= OP_CLOSE)
			{
				if (!fin || payload.length > 125)
				{
					closeQuietly(CLOSE_PROTOCOL_ERROR, "bad control frame", false);
					return;
				}

				if (opcode == OP_CLOSE)
				{
					int code = payload.length >= 2
						? ((payload[0] & 0xFF) << 8) | (payload[1] & 0xFF)
						: CLOSE_NORMAL;
					String reason = payload.length > 2
						? new String(payload, 2, payload.length - 2, StandardCharsets.UTF_8)
						: "";
					closeQuietly(code, reason, true);
					return;
				}
				else if (opcode == OP_PING)
				{
					writeControl(OP_PONG, payload);
				}
				// A pong is either an answer to a ping we never send, or unsolicited. Either way
				// there is nothing to do with it.
				continue;
			}

			if (opcode == OP_CONTINUATION)
			{
				if (message == null)
				{
					closeQuietly(CLOSE_PROTOCOL_ERROR, "continuation without a start frame", false);
					return;
				}
			}
			else
			{
				if (message != null)
				{
					closeQuietly(CLOSE_PROTOCOL_ERROR, "new message before the previous one finished", false);
					return;
				}
				messageOpcode = opcode;
				message = new ByteArrayOutputStream();
			}

			if (message.size() + payload.length > MAX_MESSAGE_BYTES)
			{
				closeQuietly(CLOSE_TOO_LARGE, "message too large", false);
				return;
			}
			message.write(payload);

			if (!fin)
			{
				continue;
			}

			byte[] complete = message.toByteArray();
			message = null;

			if (messageOpcode == OP_BINARY)
			{
				// The protocol is JSON over text frames. Refusing binary outright beats guessing.
				closeQuietly(CLOSE_UNSUPPORTED_DATA, "binary frames are not supported", false);
				return;
			}

			handler.onMessage(this, new String(complete, StandardCharsets.UTF_8));
		}
	}

	// ------------------------------------------------------------------------------------ encoding

	/** Caller must hold {@link #writeLock}. Server frames are never masked (RFC 6455 5.1). */
	private void writeFrameHeader(int opcode, int length) throws IOException
	{
		out.write(0x80 | opcode);

		if (length < 126)
		{
			out.write(length);
		}
		else if (length <= 0xFFFF)
		{
			out.write(126);
			out.write((length >>> 8) & 0xFF);
			out.write(length & 0xFF);
		}
		else
		{
			out.write(127);
			// A 64 bit length whose top four bytes are always zero: an int cannot reach them.
			out.write(0);
			out.write(0);
			out.write(0);
			out.write(0);
			out.write((length >>> 24) & 0xFF);
			out.write((length >>> 16) & 0xFF);
			out.write((length >>> 8) & 0xFF);
			out.write(length & 0xFF);
		}
	}

	private void writeControl(int opcode, byte[] payload)
	{
		try
		{
			synchronized (writeLock)
			{
				out.write(0x80 | opcode);
				out.write(payload.length);
				out.write(payload);
				out.flush();
			}
		}
		catch (IOException ignored)
		{
			// The socket is going away; the close path below handles it.
		}
	}

	/**
	 * Ends the connection once. Sends a close frame if the session ever opened, tears down the
	 * socket, and reports {@code onClose} exactly one time no matter how many paths race here.
	 *
	 * @param remote true when the peer initiated the close
	 */
	void closeQuietly(int code, String reason, boolean remote)
	{
		if (!closeReported.compareAndSet(false, true))
		{
			return;
		}

		boolean wasOpen = open;
		open = false;

		if (wasOpen)
		{
			byte[] reasonBytes = reason == null ? new byte[0] : reason.getBytes(StandardCharsets.UTF_8);
			// 125 byte control frame budget, minus the two byte status code.
			int reasonLength = Math.min(reasonBytes.length, 123);
			byte[] payload = new byte[2 + reasonLength];
			payload[0] = (byte) ((code >>> 8) & 0xFF);
			payload[1] = (byte) (code & 0xFF);
			System.arraycopy(reasonBytes, 0, payload, 2, reasonLength);
			writeControl(OP_CLOSE, payload);
		}

		try
		{
			socket.close();
		}
		catch (IOException ignored)
		{
			// Already gone.
		}

		if (wasOpen)
		{
			handler.onClose(this, code, reason, remote);
		}
	}
}
