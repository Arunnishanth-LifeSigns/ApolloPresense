package com.lsmed.connectors.wsReceiver.endpoint;

import java.io.IOException;
import org.json.JSONObject;

import com.lsmed.connectors.wsReceiver.IPatchStreamProcessor; 

public class MedAlgoProcessor implements IPatchStreamProcessor {
	
    public void processMessage(JSONObject payload, JSONObject context) throws IOException {
        System.out.println("Entered the default processor");
    }
}