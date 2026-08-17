package com.plaincandle.bankbridge.messages.response;

import com.plaincandle.bankbridge.messages.RequestType;
import lombok.Value;

/**
 * A notification, deliberately not the payload. This goes to every connected page, and most of
 * them do not want an unsolicited bank pushed at them. Interested pages re-issue GetBank.
 */
@Value
public class BankUpdated
{
	RequestType _wsType = RequestType.BankUpdated;
	long capturedAt;
}
