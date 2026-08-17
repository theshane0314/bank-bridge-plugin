package com.plaincandle.bankbridge;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;

/**
 * Boots the real {@link WebSocketManager} against a seeded {@link SnapshotStore} and drives it with
 * a real WebSocket client. Nothing under test is mocked, so this exercises the shipping origin check,
 * protocol and serialisation. The only thing it cannot cover is the client-thread container reads,
 * which need a running game.
 * <p>
 * Run with: gradlew runHarness
 */
public class LocalSocketHarness
{
	private static int passed = 0;
	private static int failed = 0;

	public static void main(String[] args) throws Exception
	{
		Gson gson = new Gson();
		BankBridgeConfig config = new BankBridgeConfig()
		{
			@Override
			public boolean rememberBank()
			{
				// Keep the harness off the real .runelite directory.
				return false;
			}
		};

		SnapshotStore store = new SnapshotStore(gson, config);

		List<OwnedItem> bank = new ArrayList<>();
		bank.add(new OwnedItem(995, 1_000_000));   // Coins
		bank.add(new OwnedItem(4151, 1));          // Abyssal whip
		bank.add(new OwnedItem(11802, 1));         // Armadyl godsword
		store.setBank(Collections.unmodifiableList(bank), 1_722_500_000_000L, "live");
		store.setInventory(Collections.singletonList(new OwnedItem(385, 12))); // Shark
		store.setEquipment(Collections.singletonList(new OwnedItem(1163, 1))); // Rune full helm
		Map<String, Integer> levels = new LinkedHashMap<>();
		levels.put("attack", 99);
		levels.put("ranged", 95);
		store.setLevels(Collections.unmodifiableMap(levels));
		store.setUsername("HarnessUser");

		WebSocketManager manager = new WebSocketManager(gson, store, config);
		manager.startUp();

		int port = -1;
		for (int i = 0; i < 50 && port == -1; i++)
		{
			Thread.sleep(100);
			port = manager.getPort();
		}
		check("server bound to a port in range", port >= 37767 && port <= 37776, "port=" + port);

		if (args.length > 0 && "serve".equals(args[0]))
		{
			// Hold the socket open with seeded data so a real browser can be pointed at it.
			System.out.println("Serving seeded data on ws://127.0.0.1:" + port + ". Ctrl+C to stop.");
			Thread.currentThread().join();
			return;
		}

		// ---------------------------------------------------------- allowed origin, full round trip
		Session allowed = connect(port, "https://osrs.plaincandle.dev");
		check("allowed origin connects", allowed.opened, "opened=" + allowed.opened);

		JsonObject hello = allowed.await();
		check("unprompted Hello on connect", hello != null && "Hello".equals(str(hello, "_wsType")), String.valueOf(hello));
		check("Hello carries username", hello != null && "HarnessUser".equals(str(hello, "username")), String.valueOf(hello));
		check("Hello reports bank available", hello != null && hello.get("bankAvailable").getAsBoolean(), String.valueOf(hello));

		allowed.send("{\"_wsType\":\"GetBank\",\"sequenceId\":42}");
		JsonObject resp = allowed.await();
		check("GetBank replies GetBank", resp != null && "GetBank".equals(str(resp, "_wsType")), String.valueOf(resp));
		check("sequenceId echoed", resp != null && resp.get("sequenceId").getAsInt() == 42, String.valueOf(resp));

		JsonObject payload = resp == null ? null : resp.getAsJsonObject("payload");
		check("payload has 3 bank items", payload != null && payload.getAsJsonArray("bank").size() == 3, String.valueOf(payload));
		check("bank item shape is {id,qty}",
			payload != null
				&& payload.getAsJsonArray("bank").get(0).getAsJsonObject().get("id").getAsInt() == 995
				&& payload.getAsJsonArray("bank").get(0).getAsJsonObject().get("qty").getAsInt() == 1_000_000,
			String.valueOf(payload));
		check("inventory included", payload != null && payload.getAsJsonArray("inventory").size() == 1, String.valueOf(payload));
		check("equipment included", payload != null && payload.getAsJsonArray("equipment").size() == 1, String.valueOf(payload));
		check("levels included", payload != null && payload.getAsJsonObject("levels").get("attack").getAsInt() == 99, String.valueOf(payload));
		check("schema version present", payload != null && payload.get("version").getAsInt() == SnapshotStore.SCHEMA_VERSION, String.valueOf(payload));

		// ------------------------------------------------------------------------ unknown request
		allowed.send("{\"_wsType\":\"Nonsense\",\"sequenceId\":7}");
		JsonObject err = allowed.await();
		check("unknown request type yields Error, not a crash",
			err != null && "Error".equals(str(err, "_wsType")) && err.get("sequenceId").getAsInt() == 7,
			String.valueOf(err));

		allowed.send("not json at all {{{");
		JsonObject err2 = allowed.await();
		check("malformed request yields Error", err2 != null && "Error".equals(str(err2, "_wsType")), String.valueOf(err2));

		// -------------------------------------------------------------- the security boundary
		Session evil = connect(port, "https://evil.example.com");
		check("disallowed origin gets no data", evil.await() == null, "received=" + evil.received);
		check("disallowed origin is closed", evil.closed, "closed=" + evil.closed);

		Session noOrigin = connectNoOrigin(port);
		check("missing origin gets no data", noOrigin.await() == null, "received=" + noOrigin.received);

		// ------------------------------------------------------------------- config gating works
		BankBridgeConfig strict = new BankBridgeConfig()
		{
			@Override
			public boolean rememberBank()
			{
				return false;
			}

			@Override
			public boolean shareInventory()
			{
				return false;
			}

			@Override
			public boolean shareLevels()
			{
				return false;
			}
		};
		PlayerData gated = new SnapshotStoreView(gson, strict, store).snapshot();
		check("shareInventory=false empties inventory", gated.getInventory().isEmpty(), String.valueOf(gated.getInventory()));
		check("shareLevels=false empties levels", gated.getLevels().isEmpty(), String.valueOf(gated.getLevels()));
		check("shareEquipment default still shares", !gated.getEquipment().isEmpty(), String.valueOf(gated.getEquipment()));

		// ------------------------------------------------------- extended payload length on the wire
		// A real bank is thousands of times bigger than the seed above, and the frame header changes
		// shape twice as it grows (7 bit length, then 16 bit, then 64 bit). Java-WebSocket parsing
		// what we encoded is an independent implementation agreeing with ours, which is the point.
		List<OwnedItem> big = new ArrayList<>();
		for (int i = 0; i < 4000; i++)
		{
			big.add(new OwnedItem(1000 + i, 1_000_000 + i));
		}
		store.setBank(Collections.unmodifiableList(big), 1_722_500_000_000L, "live");

		allowed.send("{\"_wsType\":\"GetBank\",\"sequenceId\":1234}");
		JsonObject bigResp = allowed.await();
		JsonObject bigPayload = bigResp == null ? null : bigResp.getAsJsonObject("payload");
		check("payload past 64 KiB round trips",
			bigPayload != null && bigPayload.getAsJsonArray("bank").size() == 4000,
			bigResp == null ? "null" : "size=" + gson.toJson(bigResp).length());
		check("last item of a 64 KiB+ payload survives intact",
			bigPayload != null
				&& bigPayload.getAsJsonArray("bank").get(3999).getAsJsonObject().get("id").getAsInt() == 4999,
			String.valueOf(bigPayload == null ? null : bigPayload.getAsJsonArray("bank").get(3999)));

		store.setBank(Collections.unmodifiableList(bank), 1_722_500_000_000L, "live");

		// ------------------------------------------------------------- framing, driven byte by byte
		// The RFC 6455 implementation is ours, not a library's, so the cases a browser rarely
		// produces still have to be right. A client library would smooth all of these over.
		Raw raw = Raw.connect(port, "https://osrs.plaincandle.dev");
		check("raw handshake returns 101", raw.status == 101, "status=" + raw.status);
		check("Sec-WebSocket-Accept is derived correctly", raw.acceptMatches, "accept=" + raw.accept);
		check("raw client is greeted with Hello", isType(raw.readText(), "Hello"), "no Hello");

		raw.sendFragmentedText("{\"_wsType\":\"GetBank\",\"sequ", "enceId\":99}");
		JsonObject fragged = parse(raw.readText());
		check("fragmented request is reassembled",
			fragged != null && "GetBank".equals(str(fragged, "_wsType")) && fragged.get("sequenceId").getAsInt() == 99,
			String.valueOf(fragged));

		raw.sendControl(0x9, "ping-payload".getBytes(java.nio.charset.StandardCharsets.UTF_8));
		Raw.Frame pong = raw.readFrame();
		check("ping is answered with a matching pong",
			pong != null && pong.opcode == 0xA && "ping-payload".equals(new String(pong.payload,
				java.nio.charset.StandardCharsets.UTF_8)),
			pong == null ? "null" : "opcode=" + pong.opcode);
		raw.close();

		Raw unmasked = Raw.connect(port, "https://osrs.plaincandle.dev");
		unmasked.readText();
		unmasked.sendUnmaskedText("{\"_wsType\":\"GetBank\",\"sequenceId\":1}");
		check("unmasked client frame is refused with 1002", unmasked.readCloseCode() == 1002,
			"code=" + unmasked.lastCloseCode);
		unmasked.close();

		Raw oversize = Raw.connect(port, "https://osrs.plaincandle.dev");
		oversize.readText();
		oversize.sendOversizeHeader(1_000_000);
		check("oversized message is refused with 1009", oversize.readCloseCode() == 1009,
			"code=" + oversize.lastCloseCode);
		oversize.close();

		Raw notWebsocket = Raw.connectPlainHttp(port);
		check("a plain HTTP request gets 400, not an upgrade", notWebsocket.status == 400,
			"status=" + notWebsocket.status);
		notWebsocket.close();

		allowed.close();
		manager.shutDown();
		Thread.sleep(300);
		check("port released after shutDown", manager.getPort() == -1, "port=" + manager.getPort());

		System.out.println();
		System.out.println("passed=" + passed + " failed=" + failed);
		System.exit(failed == 0 ? 0 : 1);
	}

	/** Re-reads the same seeded data through a store built on a different config. */
	private static class SnapshotStoreView extends SnapshotStore
	{
		SnapshotStoreView(Gson gson, BankBridgeConfig config, SnapshotStore source)
		{
			super(gson, config);
			setBank(source.snapshot().getBank(), source.getCapturedAt(), "live");
			setInventory(Collections.singletonList(new OwnedItem(385, 12)));
			setEquipment(Collections.singletonList(new OwnedItem(1163, 1)));
			Map<String, Integer> l = new LinkedHashMap<>();
			l.put("attack", 99);
			setLevels(Collections.unmodifiableMap(l));
		}
	}

	private static String str(JsonObject o, String k)
	{
		return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsString() : null;
	}

	private static JsonObject parse(String s)
	{
		return s == null ? null : new JsonParser().parse(s).getAsJsonObject();
	}

	private static boolean isType(String s, String type)
	{
		JsonObject o = parse(s);
		return o != null && type.equals(str(o, "_wsType"));
	}

	/**
	 * A WebSocket client written at the byte level, so the harness can send the frames a browser
	 * will not: fragmented messages, unmasked frames, a lying length header. Everything the server
	 * does with those is our own code, so a library client would only ever test the easy half.
	 */
	private static class Raw
	{
		private static final String MAGIC = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";

		private final java.net.Socket socket;
		private final java.io.DataInputStream in;
		private final java.io.OutputStream out;
		private final java.util.Random random = new java.util.Random(20260816L);

		int status = -1;
		String accept;
		boolean acceptMatches;
		int lastCloseCode = -1;

		static class Frame
		{
			int opcode;
			byte[] payload;
		}

		private Raw(int port) throws Exception
		{
			socket = new java.net.Socket("127.0.0.1", port);
			socket.setSoTimeout(3000);
			in = new java.io.DataInputStream(new java.io.BufferedInputStream(socket.getInputStream()));
			out = socket.getOutputStream();
		}

		static Raw connect(int port, String origin) throws Exception
		{
			Raw r = new Raw(port);
			byte[] nonce = new byte[16];
			r.random.nextBytes(nonce);
			String key = java.util.Base64.getEncoder().encodeToString(nonce);

			String request = "GET / HTTP/1.1\r\n"
				+ "Host: 127.0.0.1:" + port + "\r\n"
				+ "Upgrade: websocket\r\n"
				+ "Connection: Upgrade\r\n"
				+ "Sec-WebSocket-Key: " + key + "\r\n"
				+ "Sec-WebSocket-Version: 13\r\n"
				+ "Origin: " + origin + "\r\n"
				+ "\r\n";
			r.out.write(request.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1));
			r.out.flush();

			r.readHead();

			byte[] digest = java.security.MessageDigest.getInstance("SHA-1")
				.digest((key + MAGIC).getBytes(java.nio.charset.StandardCharsets.UTF_8));
			r.acceptMatches = java.util.Base64.getEncoder().encodeToString(digest).equals(r.accept);
			return r;
		}

		/** An ordinary HTTP GET with no upgrade headers at all. */
		static Raw connectPlainHttp(int port) throws Exception
		{
			Raw r = new Raw(port);
			String request = "GET / HTTP/1.1\r\nHost: 127.0.0.1:" + port + "\r\n\r\n";
			r.out.write(request.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1));
			r.out.flush();
			r.readHead();
			return r;
		}

		private void readHead() throws Exception
		{
			StringBuilder head = new StringBuilder();
			while (!head.toString().endsWith("\r\n\r\n"))
			{
				int b = in.read();
				if (b < 0)
				{
					break;
				}
				head.append((char) b);
			}

			String[] lines = head.toString().split("\r\n");
			if (lines.length > 0)
			{
				String[] parts = lines[0].split(" ");
				if (parts.length > 1)
				{
					status = Integer.parseInt(parts[1]);
				}
			}
			for (String line : lines)
			{
				if (line.toLowerCase(java.util.Locale.ROOT).startsWith("sec-websocket-accept:"))
				{
					accept = line.substring(line.indexOf(':') + 1).trim();
				}
			}
		}

		Frame readFrame()
		{
			try
			{
				int b0 = in.readUnsignedByte();
				int b1 = in.readUnsignedByte();
				long len = b1 & 0x7F;
				if (len == 126)
				{
					len = in.readUnsignedShort();
				}
				else if (len == 127)
				{
					len = in.readLong();
				}
				// A server frame must never be masked.
				if ((b1 & 0x80) != 0)
				{
					throw new IllegalStateException("server sent a masked frame");
				}
				byte[] payload = new byte[(int) len];
				in.readFully(payload);

				Frame f = new Frame();
				f.opcode = b0 & 0x0F;
				f.payload = payload;
				if (f.opcode == 0x8 && payload.length >= 2)
				{
					lastCloseCode = ((payload[0] & 0xFF) << 8) | (payload[1] & 0xFF);
				}
				return f;
			}
			catch (Exception e)
			{
				return null;
			}
		}

		String readText()
		{
			Frame f = readFrame();
			return f == null || f.opcode != 0x1
				? null
				: new String(f.payload, java.nio.charset.StandardCharsets.UTF_8);
		}

		int readCloseCode()
		{
			for (int i = 0; i < 4; i++)
			{
				Frame f = readFrame();
				if (f == null)
				{
					return lastCloseCode;
				}
				if (f.opcode == 0x8)
				{
					return lastCloseCode;
				}
			}
			return lastCloseCode;
		}

		private void writeFrame(int opcode, boolean fin, byte[] payload, boolean mask) throws Exception
		{
			out.write((fin ? 0x80 : 0x00) | opcode);
			int lenByte = mask ? 0x80 : 0x00;
			if (payload.length < 126)
			{
				out.write(lenByte | payload.length);
			}
			else if (payload.length <= 0xFFFF)
			{
				out.write(lenByte | 126);
				out.write((payload.length >>> 8) & 0xFF);
				out.write(payload.length & 0xFF);
			}
			else
			{
				out.write(lenByte | 127);
				for (int i = 7; i >= 0; i--)
				{
					out.write((int) ((((long) payload.length) >>> (8 * i)) & 0xFF));
				}
			}

			if (mask)
			{
				byte[] key = new byte[4];
				random.nextBytes(key);
				out.write(key);
				byte[] masked = new byte[payload.length];
				for (int i = 0; i < payload.length; i++)
				{
					masked[i] = (byte) (payload[i] ^ key[i & 3]);
				}
				out.write(masked);
			}
			else
			{
				out.write(payload);
			}
			out.flush();
		}

		void sendFragmentedText(String first, String rest) throws Exception
		{
			writeFrame(0x1, false, first.getBytes(java.nio.charset.StandardCharsets.UTF_8), true);
			writeFrame(0x0, true, rest.getBytes(java.nio.charset.StandardCharsets.UTF_8), true);
		}

		void sendUnmaskedText(String text) throws Exception
		{
			writeFrame(0x1, true, text.getBytes(java.nio.charset.StandardCharsets.UTF_8), false);
		}

		void sendControl(int opcode, byte[] payload) throws Exception
		{
			writeFrame(opcode, true, payload, true);
		}

		/** Declares a huge payload without sending it, to prove the length is checked first. */
		void sendOversizeHeader(long declaredLength) throws Exception
		{
			out.write(0x80 | 0x1);
			out.write(0x80 | 127);
			for (int i = 7; i >= 0; i--)
			{
				out.write((int) ((declaredLength >>> (8 * i)) & 0xFF));
			}
			out.flush();
		}

		void close()
		{
			try
			{
				socket.close();
			}
			catch (Exception ignored)
			{
				// Done with it either way.
			}
		}
	}

	private static void check(String name, boolean ok, String detail)
	{
		if (ok)
		{
			passed++;
			System.out.println("  PASS  " + name);
		}
		else
		{
			failed++;
			System.out.println("  FAIL  " + name + "   -> " + detail);
		}
	}

	private static Session connect(int port, String origin) throws Exception
	{
		Map<String, String> headers = new HashMap<>();
		headers.put("Origin", origin);
		return open(port, headers);
	}

	private static Session connectNoOrigin(int port) throws Exception
	{
		return open(port, new HashMap<>());
	}

	private static Session open(int port, Map<String, String> headers) throws Exception
	{
		Session s = new Session(new URI("ws://127.0.0.1:" + port), headers);
		s.connectBlocking(3, TimeUnit.SECONDS);
		return s;
	}

	private static class Session extends WebSocketClient
	{
		volatile boolean opened = false;
		volatile boolean closed = false;
		volatile String received = null;
		private volatile CountDownLatch latch = new CountDownLatch(1);

		Session(URI uri, Map<String, String> headers)
		{
			super(uri, headers);
		}

		JsonObject await()
		{
			try
			{
				latch.await(2, TimeUnit.SECONDS);
			}
			catch (InterruptedException e)
			{
				Thread.currentThread().interrupt();
			}
			String msg = received;
			received = null;
			latch = new CountDownLatch(1);
			return msg == null ? null : new JsonParser().parse(msg).getAsJsonObject();
		}

		@Override
		public void onOpen(ServerHandshake handshakedata)
		{
			opened = true;
		}

		@Override
		public void onMessage(String message)
		{
			received = message;
			latch.countDown();
		}

		@Override
		public void onClose(int code, String reason, boolean remote)
		{
			closed = true;
			latch.countDown();
		}

		@Override
		public void onError(Exception ex)
		{
		}
	}
}
