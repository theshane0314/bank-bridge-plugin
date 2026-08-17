package com.plaincandle.bankbridge;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.RuneLite;

/**
 * Holds the most recent snapshot of everything we are willing to serve.
 * <p>
 * The whole design of this plugin lives in this class's threading contract. Every field is written
 * only from the client thread (from item-container / stat events, where reading the game client is
 * legal and free) and read only as an immutable reference from the WebSocket threads. That means a
 * socket request never has to touch the game client, so it can never block it, which is precisely
 * the bug that made the third-party bank-sync plugin unusable.
 */
@Slf4j
@Singleton
public class SnapshotStore
{
	static final int SCHEMA_VERSION = 1;

	private static final File STORE_DIR = new File(RuneLite.RUNELITE_DIR, "bank-bridge");
	private static final int STORE_VERSION = 1;

	private final Gson gson;
	private final BankBridgeConfig config;

	private volatile List<OwnedItem> bank = Collections.emptyList();
	private volatile long bankCapturedAt = 0L;
	private volatile String bankSource = "none";

	private volatile List<OwnedItem> inventory = Collections.emptyList();
	private volatile List<OwnedItem> equipment = Collections.emptyList();
	private volatile Map<String, Integer> levels = Collections.emptyMap();
	private volatile String username = null;

	@Inject
	SnapshotStore(Gson gson, BankBridgeConfig config)
	{
		this.gson = gson;
		this.config = config;
	}

	/** The on-disk shape. Deliberately item ids and quantities only: no name, no account hash. */
	@Value
	private static class StoredBank
	{
		int version;
		long capturedAt;
		List<OwnedItem> items;
	}

	// ---------------------------------------------------------------- writes (client thread only)

	void setBank(List<OwnedItem> items, long capturedAt, String source)
	{
		this.bank = items;
		this.bankCapturedAt = capturedAt;
		this.bankSource = source;
	}

	void setInventory(List<OwnedItem> items)
	{
		this.inventory = items;
	}

	void setEquipment(List<OwnedItem> items)
	{
		this.equipment = items;
	}

	void setLevels(Map<String, Integer> levels)
	{
		this.levels = levels;
	}

	void setUsername(String username)
	{
		this.username = username;
	}

	/** Wipes everything that identifies an account. Called on logout and on shutdown. */
	void clear()
	{
		this.bank = Collections.emptyList();
		this.bankCapturedAt = 0L;
		this.bankSource = "none";
		this.inventory = Collections.emptyList();
		this.equipment = Collections.emptyList();
		this.levels = Collections.emptyMap();
		this.username = null;
	}

	// ------------------------------------------------------------------------------------- reads

	boolean isBankAvailable()
	{
		return bankCapturedAt > 0L;
	}

	long getCapturedAt()
	{
		return bankCapturedAt;
	}

	String getUsername()
	{
		return username;
	}

	/**
	 * Builds the payload served to a page. Safe to call from any thread: it only reads volatile
	 * references to already-immutable lists.
	 */
	PlayerData snapshot()
	{
		return new PlayerData(
			SCHEMA_VERSION,
			bankCapturedAt,
			username,
			isBankAvailable(),
			bankSource,
			bank,
			config.shareInventory() ? inventory : Collections.emptyList(),
			config.shareEquipment() ? equipment : Collections.emptyList(),
			config.shareLevels() ? levels : Collections.<String, Integer>emptyMap()
		);
	}

	// ---------------------------------------------------------------------- disk (executor only)

	/**
	 * Must not be called from the client thread, because it does blocking file I/O.
	 */
	void persist(long accountHash)
	{
		if (!config.rememberBank() || accountHash == -1L || !isBankAvailable())
		{
			return;
		}

		File target = fileFor(accountHash);
		File tmp = new File(target.getPath() + ".tmp");
		try
		{
			if (!STORE_DIR.exists() && !STORE_DIR.mkdirs())
			{
				log.debug("Could not create {}", STORE_DIR);
				return;
			}

			String json = gson.toJson(new StoredBank(STORE_VERSION, bankCapturedAt, bank));
			Files.write(tmp.toPath(), json.getBytes(StandardCharsets.UTF_8));
			Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
			log.debug("Persisted {} bank items", bank.size());
		}
		catch (IOException e)
		{
			log.debug("Failed to persist bank snapshot", e);
			//noinspection ResultOfMethodCallIgnored
			tmp.delete();
		}
	}

	/**
	 * Must not be called from the client thread, because it does blocking file I/O.
	 *
	 * @return true if a cached bank was restored, false otherwise.
	 */
	boolean restore(long accountHash)
	{
		if (!config.rememberBank() || accountHash == -1L || isBankAvailable())
		{
			// Never let a cached bank overwrite one we have already read live this session.
			return false;
		}

		File source = fileFor(accountHash);
		if (!source.isFile())
		{
			return false;
		}

		try
		{
			String json = new String(Files.readAllBytes(source.toPath()), StandardCharsets.UTF_8);
			StoredBank stored = gson.fromJson(json, StoredBank.class);
			if (stored == null || stored.getItems() == null || stored.getVersion() != STORE_VERSION)
			{
				return false;
			}

			setBank(Collections.unmodifiableList(stored.getItems()), stored.getCapturedAt(), "cache");
			log.debug("Restored {} cached bank items", stored.getItems().size());
			return true;
		}
		catch (IOException | JsonSyntaxException e)
		{
			log.debug("Failed to restore bank snapshot", e);
			return false;
		}
	}

	void deleteStored(long accountHash)
	{
		if (accountHash == -1L)
		{
			return;
		}
		//noinspection ResultOfMethodCallIgnored
		fileFor(accountHash).delete();
	}

	private static File fileFor(long accountHash)
	{
		return new File(STORE_DIR, "bank-" + Long.toUnsignedString(accountHash) + ".json");
	}
}
