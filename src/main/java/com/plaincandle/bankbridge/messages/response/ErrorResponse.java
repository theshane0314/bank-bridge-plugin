package com.plaincandle.bankbridge.messages.response;

import com.plaincandle.bankbridge.messages.RequestType;
import lombok.Value;

@Value
public class ErrorResponse
{
	RequestType _wsType = RequestType.Error;
	int sequenceId;
	String error;
}
