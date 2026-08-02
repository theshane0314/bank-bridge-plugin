package com.plaincandle.bankbridge.messages.response;

import com.plaincandle.bankbridge.messages.RequestType;
import lombok.Value;

/**
 * Sent unprompted on connect so a page can render "connected as X, bank captured at Y" without
 * having to make a request first.
 */
@Value
public class Hello
{
	RequestType _wsType = RequestType.Hello;
	String pluginVersion;
	int schemaVersion;
	String username;
	boolean bankAvailable;
	long capturedAt;
}
