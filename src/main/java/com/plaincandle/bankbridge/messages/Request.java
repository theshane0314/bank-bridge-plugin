package com.plaincandle.bankbridge.messages;

import lombok.Data;

/**
 * Deserialised from client JSON, so this is a mutable bean with defaults rather than a
 * {@code @Value}. Gson bypasses the constructor, and an unknown {@code _wsType} must land as
 * {@code null} rather than blowing up the read.
 */
@Data
public class Request
{
	RequestType _wsType;
	int sequenceId;
}
