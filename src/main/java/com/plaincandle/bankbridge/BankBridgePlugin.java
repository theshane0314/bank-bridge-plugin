package com.plaincandle.bankbridge;

import com.google.inject.Provides;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.Player;
import net.runelite.api.Skill;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.StatChanged;
import net.runelite.api.gameval.InventoryID;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.game.ItemManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.task.Schedule;

@Slf4j
@PluginDescriptor(
	name = "Bank Bridge",
	description = "Serves your bank, inventory, equipment and levels to gear-planning sites over a local-only WebSocket",
	tags = {"bank", "sync", "gear", "dps", "websocket", "local", "setup", "inventory"}
)
public class BankBridgePlugin extends Plugin
{
	static final String VERSION = "1.0.0";

	/** How long to wait after the last bank change before writing the snapshot to disk. */
	private static final int PERSIST_DEBOUNCE_SECONDS = 5;

	@Inject
	private Client client;

	@Inject
	private ItemManager itemManager;

	@Inject
	private ScheduledExecutorService executor;

	@Inject
	private SnapshotStore store;

	@Inject
	private WebSocketManager webSocketManager;

	@Inject
	private BankBridgeConfig config;

	private final AtomicBoolean persistScheduled = new AtomicBoolean(false);
	private volatile long accountHash = -1L;
	private String lastUsername = null;

	@Provides
	BankBridgeConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(BankBridgeConfig.class);
	}

	@Override
	protected void startUp()
	{
		log.debug("Bank Bridge starting");
		webSocketManager.startUp();
	}

	@Override
	protected void shutDown()
	{
		log.debug("Bank Bridge stopping");
		webSocketManager.shutDown();
		store.clear();
		accountHash = -1L;
		lastUsername = null;
	}

	/**
	 * Retries the bind if every port in the range was taken at start-up. A no-op once listening.
	 */
	@Schedule(period = 30, unit = ChronoUnit.SECONDS, asynchronous = true)
	public void ensureSocketActive()
	{
		webSocketManager.ensureActive();
	}

	// ------------------------------------------------------------------------ client-thread reads

	@Subscribe
	public void onItemContainerChanged(ItemContainerChanged event)
	{
		final ItemContainer container = event.getItemContainer();

		switch (event.getContainerId())
		{
			case InventoryID.BANK:
			{
				List<OwnedItem> items = readContainer(container, true);
				long capturedAt = System.currentTimeMillis();
				store.setBank(items, capturedAt, "live");
				schedulePersist();
				webSocketManager.broadcastBankUpdated(capturedAt);
				break;
			}
			case InventoryID.INV:
				store.setInventory(readContainer(container, false));
				break;
			case InventoryID.WORN:
				store.setEquipment(readContainer(container, false));
				break;
			default:
				break;
		}
	}

	@Subscribe
	public void onStatChanged(StatChanged event)
	{
		store.setLevels(readLevels());
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		final GameState state = event.getGameState();

		if (state == GameState.LOGGED_IN)
		{
			long hash = client.getAccountHash();
			if (hash != accountHash)
			{
				// Switched account (or logged in for the first time): drop everything belonging to
				// the previous one before restoring the new one's cache.
				store.clear();
				accountHash = hash;
				executor.execute(() -> store.restore(hash));
			}
		}
		else if (state == GameState.LOGIN_SCREEN)
		{
			// Back at the login screen the account is gone, so stop serving its data. Hopping is
			// deliberately not handled here — it keeps the same account, and clearing would drop
			// the bank snapshot for no reason.
			store.clear();
			accountHash = -1L;
			lastUsername = null;
		}
	}

	@Subscribe
	public void onGameTick(GameTick event)
	{
		String current = null;
		if (client.getGameState() == GameState.LOGGED_IN)
		{
			Player p = client.getLocalPlayer();
			if (p != null)
			{
				current = p.getName();
			}
		}

		if (!Objects.equals(lastUsername, current))
		{
			lastUsername = current;
			store.setUsername(current);
		}
	}

	// --------------------------------------------------------------------------------- internals

	/**
	 * Reads an item container into an immutable list. Must run on the client thread.
	 *
	 * @param skipPlaceholders drop bank placeholders — they are items you do <em>not</em> own, and
	 *                         handing them to a "what do I own" consumer would be a lie.
	 */
	private List<OwnedItem> readContainer(ItemContainer container, boolean skipPlaceholders)
	{
		if (container == null)
		{
			return Collections.emptyList();
		}

		final Item[] items = container.getItems();
		final List<OwnedItem> out = new ArrayList<>(items.length);

		for (Item item : items)
		{
			final int id = item.getId();
			final int qty = item.getQuantity();

			if (id <= 0 || qty <= 0)
			{
				// Empty slot, or a placeholder (which the client models as quantity 0).
				continue;
			}

			if (skipPlaceholders && itemManager.getItemComposition(id).getPlaceholderTemplateId() != -1)
			{
				continue;
			}

			out.add(new OwnedItem(id, qty));
		}

		return Collections.unmodifiableList(out);
	}

	/** Real, unboosted levels. Must run on the client thread. */
	private Map<String, Integer> readLevels()
	{
		Map<Skill, Integer> raw = new EnumMap<>(Skill.class);
		for (Skill skill : Skill.values())
		{
			raw.put(skill, client.getRealSkillLevel(skill));
		}

		Map<String, Integer> levels = new java.util.LinkedHashMap<>(raw.size());
		for (Map.Entry<Skill, Integer> e : raw.entrySet())
		{
			levels.put(e.getKey().getName().toLowerCase(Locale.ROOT), e.getValue());
		}
		return Collections.unmodifiableMap(levels);
	}

	/**
	 * Coalesces a burst of bank changes (depositing an inventory one item at a time fires an event
	 * per item) into a single disk write, off the client thread.
	 */
	private void schedulePersist()
	{
		if (!config.rememberBank())
		{
			return;
		}

		if (persistScheduled.compareAndSet(false, true))
		{
			final long hash = accountHash;
			executor.schedule(() -> {
				persistScheduled.set(false);
				store.persist(hash);
			}, PERSIST_DEBOUNCE_SECONDS, TimeUnit.SECONDS);
		}
	}
}
