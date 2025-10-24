package com.lsmed.connectors.wsReceiver;

import org.json.JSONObject;


public interface IPatchStreamProcessor {
	
	void processMessage (JSONObject payload, JSONObject context) throws Exception;
}
