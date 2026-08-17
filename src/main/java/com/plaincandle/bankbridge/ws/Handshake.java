package com.plaincandle.bankbridge.ws;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * The opening HTTP request of a WebSocket connection, parsed.
 * <p>
 * This exists so the origin check has something to read. The header map is the security surface of
 * this plugin: {@code Origin} is what tells us which website is on the other end, so parsing it has
 * to be boring and strict rather than clever.
 */
public final class Handshake
{
	/**
	 * A handshake is a few hundred bytes. Anything larger is not a browser, and reading it
	 * unbounded would let an unauthenticated peer grow our heap before we ever check who it is.
	 */
	private static final int MAX_HANDSHAKE_BYTES = 8192;

	private final String method;
	private final String resourceDescriptor;
	private final Map<String, String> fields;

	private Handshake(String method, String resourceDescriptor, Map<String, String> fields)
	{
		this.method = method;
		this.resourceDescriptor = resourceDescriptor;
		this.fields = fields;
	}

	public String getMethod()
	{
		return method;
	}

	/** The request target, e.g. {@code "/"}. */
	public String getResourceDescriptor()
	{
		return resourceDescriptor;
	}

	/**
	 * @return the header value, or an empty string when the header is absent. Never null, so callers
	 *         cannot accidentally treat "absent" as "allowed" by skipping a null check.
	 */
	public String getFieldValue(String name)
	{
		String v = fields.get(name.toLowerCase(Locale.ROOT));
		return v == null ? "" : v;
	}

	public boolean hasField(String name)
	{
		return fields.containsKey(name.toLowerCase(Locale.ROOT));
	}

	/**
	 * Reads the request line and headers off the wire, stopping at the blank line that ends them.
	 * Deliberately reads one byte at a time: the bytes after the blank line are WebSocket frames,
	 * not HTTP, so a block read could swallow the start of the first frame.
	 *
	 * @throws IOException if the peer disconnects, sends more than {@link #MAX_HANDSHAKE_BYTES}, or
	 *                     sends something that is not a well formed HTTP request head
	 */
	public static Handshake read(InputStream in) throws IOException
	{
		byte[] buf = new byte[MAX_HANDSHAKE_BYTES];
		int len = 0;

		while (true)
		{
			int b = in.read();
			if (b < 0)
			{
				throw new EOFException("Connection closed during handshake");
			}

			if (len == buf.length)
			{
				throw new IOException("Handshake exceeded " + MAX_HANDSHAKE_BYTES + " bytes");
			}
			buf[len++] = (byte) b;

			if (len >= 4
				&& buf[len - 4] == '\r' && buf[len - 3] == '\n'
				&& buf[len - 2] == '\r' && buf[len - 1] == '\n')
			{
				break;
			}
		}

		// ISO-8859-1 rather than UTF-8: HTTP/1.1 header bytes are latin-1, and decoding them as
		// UTF-8 would let malformed input turn into replacement characters instead of failing.
		String head = new String(buf, 0, len, StandardCharsets.ISO_8859_1);
		String[] lines = head.split("\r\n");
		if (lines.length == 0 || lines[0].isEmpty())
		{
			throw new IOException("Empty request line");
		}

		String[] requestLine = lines[0].split(" ");
		if (requestLine.length < 3)
		{
			throw new IOException("Malformed request line: " + lines[0]);
		}

		Map<String, String> fields = new HashMap<>();
		for (int i = 1; i < lines.length; i++)
		{
			String line = lines[i];
			if (line.isEmpty())
			{
				continue;
			}

			int colon = line.indexOf(':');
			if (colon <= 0)
			{
				throw new IOException("Malformed header: " + line);
			}

			String name = line.substring(0, colon).trim().toLowerCase(Locale.ROOT);
			String value = line.substring(colon + 1).trim();
			// Last one wins. A duplicated Origin is not something a browser does, and merging them
			// would only create a string that parses as neither host.
			fields.put(name, value);
		}

		return new Handshake(requestLine[0], requestLine[1], fields);
	}
}
