package com.plaincandle.bankbridge.messages;

public enum RequestType
{
	/** Server -> client, sent unprompted as soon as a connection is accepted. */
	Hello,
	/** Client -> server. Returns the full owned-items payload. */
	GetBank,
	/** Server -> client, broadcast when the cached bank snapshot changes. */
	BankUpdated,
	/** Server -> client, sent in place of a response when a request could not be served. */
	Error
}
