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
 * a real WebSocket client. Nothing under test is mocked — this exercises the shipping origin check,
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
			System.out.println("Serving seeded data on ws://127.0.0.1:" + port + " — Ctrl+C to stop.");
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
