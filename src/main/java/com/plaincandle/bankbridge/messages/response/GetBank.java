package com.plaincandle.bankbridge.messages.response;

import com.plaincandle.bankbridge.PlayerData;
import com.plaincandle.bankbridge.messages.RequestType;
import lombok.Value;

@Value
public class GetBank
{
	RequestType _wsType = RequestType.GetBank;
	int sequenceId;
	PlayerData payload;
}
