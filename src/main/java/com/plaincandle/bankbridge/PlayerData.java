package com.plaincandle.bankbridge;

import java.util.List;
import java.util.Map;
import lombok.Value;

/**
 * The whole payload served over the socket. Immutable and pre-built on the client thread, so the
 * WebSocket threads only ever hand out a reference. They never read the game client.
 */
@Value
public class PlayerData
{
	/** Schema version, so the consuming site can refuse a payload it does not understand. */
	int version;

	/** Epoch millis the bank half of this snapshot was captured. 0 if never captured. */
	long capturedAt;

	String username;

	/** False until the bank has been opened at least once, and no cached snapshot was restored. */
	boolean bankAvailable;

	/** "live" if the bank was read this session, "cache" if restored from disk. */
	String bankSource;

	List<OwnedItem> bank;
	List<OwnedItem> inventory;
	List<OwnedItem> equipment;

	/** Real (unboosted) levels keyed by lowercase skill name. */
	Map<String, Integer> levels;
}
