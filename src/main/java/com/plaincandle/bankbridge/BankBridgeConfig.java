package com.plaincandle.bankbridge;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;

@ConfigGroup(BankBridgeConfig.GROUP)
public interface BankBridgeConfig extends Config
{
	String GROUP = "bankbridge";

	@ConfigItem(
		keyName = "shareInventory",
		name = "Share inventory",
		description = "Include the items currently in your inventory in the payload.",
		position = 1
	)
	default boolean shareInventory()
	{
		return true;
	}

	@ConfigItem(
		keyName = "shareEquipment",
		name = "Share worn equipment",
		description = "Include the items you are currently wearing in the payload.",
		position = 2
	)
	default boolean shareEquipment()
	{
		return true;
	}

	@ConfigItem(
		keyName = "shareLevels",
		name = "Share levels",
		description = "Include your real (unboosted) skill levels, so a site can stop assuming a maxed account.",
		position = 3
	)
	default boolean shareLevels()
	{
		return true;
	}

	@ConfigItem(
		keyName = "rememberBank",
		name = "Remember bank between sessions",
		description = "Save the last bank snapshot under .runelite/bank-bridge so a site can read it before you have opened the bank this session. Never leaves your machine.",
		position = 4
	)
	default boolean rememberBank()
	{
		return true;
	}

	@ConfigItem(
		keyName = "extraOrigins",
		name = "Extra allowed sites",
		description = "Comma-separated extra hostnames allowed to connect, for developing your own page. Only add sites you trust — any allowed site can read the data above.",
		position = 5
	)
	default String extraOrigins()
	{
		return "";
	}
}
