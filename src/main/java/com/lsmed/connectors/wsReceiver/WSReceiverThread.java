package com.lsmed.connectors.wsReceiver;

import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.json.JSONArray;
import org.json.JSONObject;

import io.socket.client.IO;
import io.socket.client.Socket;
import io.socket.emitter.Emitter;
import io.socket.engineio.client.transports.WebSocket; 

public class WSReceiverThread extends Thread {

	private Socket socket;
	
	private String facilityId;
	
	private String patchId;
	
	private String patientId;
	
	private String serviceId;
	
	private String providerId;

	private String admissionId;

	private String patientRef;

	private boolean patientRefFlag = false;

	private static ConfigurationManager configManager = ConfigurationManager.getInstance(); // Get the instance once
	public static String serverUrl = configManager.getServerUrl();
	private static String fallback_user = configManager.getProperty("fallback.user");
	private static String fallback_pass = configManager.getProperty("fallback.pass");
	private static final String source = configManager.getSource();

    private static final Logger LOG = LogManager.getLogger(WSReceiverThread.class.getSimpleName());

    private static final Logger MSGLOG = LogManager.getLogger("MSGLOG");
    
	private String token;
	
	private long prevMsgCounter = 0;
	
	private long msgCounter = 0;
	
	private long lastRcvd = 0L; // Instant.now().getEpochSecond();
	
	private long lastStreamed;
	
	private boolean enableDataDump = false;
    
    private JSONObject context = new JSONObject();
    
    public boolean Stop = false;
    
    private volatile boolean stopRequested = false;
    
    private volatile boolean restartRequested = false;
    
    private int connectCount = 0;
    
	/**
	 * ArrayList holds the list of processors that can handle this message
	 */
	ArrayList<IPatchStreamProcessor> streamProcessor = new ArrayList<IPatchStreamProcessor>();

	/**
	 * Receiver Thread with Patch ID and Service that is bound. This will be the initial
	 * service. New services can be added in the future.
	 * 
	 * @param patchId
	 * @param patientId
	 * @param serviceId
	 * @param providerId
	 */
	public WSReceiverThread(String facilityId, String patchId, String patientId, String serviceId, String providerId, long last_streamed, String admissionId) {
		
		/* Associate to common thread group */
		super (WSThreadManager.getInstance().getThreadGroup(), patchId);
		
		this.setPatchId(patchId);
		this.setPatientId(patientId);
		this.setProviderId(providerId);
		this.setServiceId(serviceId);
		this.setFacilityId(facilityId);	
		this.admissionId = admissionId;	
		
		// <-- MODIFIED THIS ENTIRE BLOCK
		if (admissionId != null && !admissionId.isEmpty()) {
            this.patientRef = facilityId + "-" + patientId + "-" + admissionId;
            this.patientRefFlag = true;
            LOG.info("[{}] Initialized with constructed patientRef: {}", patchId, this.patientRef);
        } else {
            this.patientRef = facilityId + "-" + patientId + "-";
            this.patientRefFlag = false;
            LOG.warn("[{}] admissionId was not provided on initialization. Will attempt to construct patientRef from data packet.", patchId);
        }

		attachService (serviceId, providerId);
		this.lastStreamed = last_streamed;
		
	}

	public void attachService (String serviceId, String providerId) {

		ServiceManager svcMgr = ServiceManager.getInstance();

		/* If no Provider is defined, use default */
		IPatchStreamProcessor p = null;
		if (providerId == null) {
			p = svcMgr.getDefaultProvider(serviceId);
		} else {
			
			p = svcMgr.getMappedProvider(serviceId, providerId);
		}
		
		if (p != null) {
			streamProcessor.add(p);
		} else {			
			LOG.fatal("No Stream Processing Providers found. Ignored: ", patchId);
		}	
	}
	
	@Override
	public void run() {
		
		LOG.info ("Thread [" + getName() + "] starting.");

		token = AuthHelper.getToken(fallback_user,fallback_pass);
		if(token == null) {
			LOG.warn("[{}] Problem obtaining token", patchId);
			token = AuthHelper.getToken(fallback_user,fallback_pass);
		}
		else {
			LOG.debug("[{}]: Token obtained", patchId);
		}
		
		IO.Options options = IO.Options.builder()
			    .setForceNew(true)
			    .setMultiplex(false)
			    .setUpgrade(true)
			    .setRememberUpgrade(false)
			    .setQuery(null)
			    .setExtraHeaders(null)				
			    .setReconnection(true)
			    .setReconnectionAttempts(Integer.MAX_VALUE)
			    .setReconnectionDelay(1000)
			    .setReconnectionDelayMax(5000)
			    .setRandomizationFactor(0.5)				
			    .setTransports(new String[] { WebSocket.NAME })
		        .setAuth(Collections.singletonMap("token", token))
		        .setTimeout(20000)
		        .build();	
 
        
		try {
			socket = IO.socket(URI.create(serverUrl), options);
			
			socket.on(Socket.EVENT_CONNECT, args -> {
			    LOG.info("[{}]: socket connection established!", patchId);
			    connectCount++;
			    prevMsgCounter = -1;
			    msgCounter = 0;
			    context.clear();
			    JSONObject patchJson = new JSONObject(Collections.singletonMap("patch_id", patchId));
				try {
					socket.emit("patch_id", patchJson);
					LOG.info("[{}] socket emitted, socket.connected {}", patchId, socket.connected());
				} catch (Exception e) {
					LOG.warn("[{}] socket hasn't emitted", patchId);
				}
			}); socket.connect();
			
			// Add disconnect event handler
            socket.on(Socket.EVENT_DISCONNECT, args -> {
            	StringBuffer buffer = new StringBuffer(128);
        		buffer.append("PatchId: ").append(patchId).append(", socket disconnected!");
        		LOG.warn(new String(buffer)); 
            });
            
			
		} catch (Exception e) {
			LOG.fatal("Failed to establish socket connection. Skipping patch: {}, Message: {}", patchId, e.getMessage());
		    LOG.debug("Failed to establish socket connection. Skipping patch: {}, Details: {}", patchId, e);

			return;
		}
		

		socket.on("update", new Emitter.Listener() {

		    public void call(Object... args) {

		    	String data = args[0].toString();
		    	msgCounter++;
		    	lastRcvd = Instant.now().getEpochSecond();
		    	
		    	JSONObject Data = new JSONObject(data);
		    	Data.put("pgroupName", "Unknown");
		    	Data.put("patientName", "Unknown");
		    	Data.put("age", -1);
		    	Data.put("gender", "Unknown");
				Data.put("AdmissionTime", "0");

				if(!patientRefFlag) {
					try {
						String FacilityId = Data.getString("FacilityId");
						String PatientId = Data.getString("PatientId");
						String AdmissionId = Data.getString("AdmissionId");
						// patientRef = FacilityId + "-" + PatientId + "-" + AdmissionId;
						// Check if all required IDs are present and not empty
						if (FacilityId != null && !FacilityId.isEmpty() && PatientId != null && !PatientId.isEmpty() && AdmissionId != null && !AdmissionId.isEmpty()) {
							patientRef = FacilityId + "-" + PatientId + "-" + AdmissionId;	
							patientRefFlag = true;
							admissionId = AdmissionId; // Set local admissionId
							DataReceiver.requestAdmissionIdUpdate(patchId, admissionId);
						}
						LOG.info("[{}] patientRef successfully constructed and set: {} {}", patchId, patientRef, patientRefFlag);
					} catch (Exception e) {
						patientRefFlag = false;
					}
				}

		    	if (!(Data.has("SensorData") && Data.get("SensorData") instanceof JSONArray)) {
		    		Data.put("SensorData", new JSONArray());
		    	}

				// MSGLOG.debug(data);
		    	if (enableDataDump) {
		    		MSGLOG.debug(Data);
		    		MSGLOG.info("Data Chunk: " + msgCounter + " LastReceived: " + lastRcvd);
		    	}

		    	if(context.has("SendStatus")) {
		    		int response = context.getInt("SendStatus");
					if (response >= 200 && response < 300) {
						MSGLOG.info("[" + patchId + "] Data Sent to API successfully with status: " + response);
						lastStreamed = Instant.now().getEpochSecond();
		    		} else {
		    			MSGLOG.error(patchId + " data not sent: " + response);
		    		}
		    		
		    	}

	            // Iterate through the processors and process the data
	            for (IPatchStreamProcessor processor : streamProcessor) { 
	                try {
	                    processor.processMessage(Data, context);
	                } catch (Exception e) {
	                    LOG.error("[{}] Message not Processed. Message: {}", patchId, e.getMessage());
        				LOG.debug("[{}] Message not Processed. Details: {}", patchId, e);
	                }
	            }
		    }
		});	
		
		try {
	        while (!stopRequested) { // Check the flag here
	            Thread.sleep(30 * 1000);
	            doHousekeeping();
	            if (restartRequested) {
                    restartRequested = false;
                    WSReceiverThread newThread = new WSReceiverThread(facilityId, patchId, patientId, serviceId, providerId, lastStreamed, this.admissionId);
                    newThread.start();
//                    LOG.info("Starting thread again....");
                    return; // End the current thread
                }
	        }
	    } catch (InterruptedException ie) {
	        LOG.info("Thread [" + getName() + "] interrupted.");
	    } finally { 
	        if (socket != null) {
	            socket.disconnect(); 
	            socket.close();
	            LOG.info("Socket closed for patchId: " + patchId);
	        }
	    }
		
	}

	public void doHousekeeping() {
		
		if(enableDataDump) {
			StringBuffer buffer = new StringBuffer(128);
			buffer.append("PatchId: ").append(patchId)
				.append(", Messages Rcvd: ").append(msgCounter)
				.append(", Last Rcvd: ").append(lastRcvd)
				.append(", Seconds ago: ").append((lastRcvd != 0) ? (Instant.now().getEpochSecond() - lastRcvd) : "NA");
			LOG.info(new String(buffer));
		}
		
		if (msgCounter == 0) {
	    	
			LOG.warn("0 messages received for PatchId: [{}]: Socket connection: {}, connectCount", patchId, socket.connected(), connectCount);

			if (connectCount > 0 && !socket.connected()) {
				LOG.warn("[{}] This patch connected previously but is now disconnected with 0 messages. Forcing restart.", patchId);
				interrupt();
				return;
			}
		} else {
		
			if ((msgCounter - prevMsgCounter) == 0) {
				LOG.warn("[{}]: No new messages received. MsgCount: {}, socket: {}.", patchId, msgCounter, socket.isActive());
				
			}
			
			if (lastRcvd > 0 && Instant.now().getEpochSecond() - lastRcvd > 120) {
			    LOG.info("Disconnected for {} seconds. Reconnecting...", Instant.now().getEpochSecond() - lastRcvd);
			    interrupt();
			    return;
			}

		}
		
		prevMsgCounter = msgCounter;
		
		// // Check if API info is populated
	    // if (!api_flag) { 
	    //     LOG.info("API info missing. Re-fetching for PatchId: {}", patchId);
	    //     getInfoFromAPI(patchId, facilityId);
	    // }
	}

	@Override
    public void interrupt() {
        super.interrupt();
        
        if(Stop == false) {
        	restartRequested = true;
			LOG.info("Interrupt [" + getName() + "] not sending discharge message as Stop is false");
        } else {
        	stopRequested = true;
			LOG.info("Interrupt [" + getName() + "] sending discharge message as Stop is true");
			// Send Discharge message
			for (IPatchStreamProcessor processor : streamProcessor) {
				try {
					JSONObject Data = new JSONObject();
					String finalPatientRef = this.patientRef;
					if (finalPatientRef == null || finalPatientRef.isEmpty()) {
						finalPatientRef = "Unknown";
						LOG.warn("[{}] patientRef was null or empty at discharge. Setting to 'Unknown'.", this.patchId);
					}
					Data.put("patientRef", finalPatientRef);
					Data.put("BiosensorStatus", "Discharged");
					Data.put("PatchId", this.patchId);
					Data.put("TimeStamp", Instant.now().getEpochSecond());
					Data.put("FacilityId", this.facilityId);
					Data.put("source", source);
					JSONObject discharge = new JSONObject();
					discharge.put("Discharge", true);
					processor.processMessage(Data, discharge);
					LOG.info("[{}] Discharge Message Created. Data: {}",patchId, Data.toString());
				} catch (Exception e) {
					LOG.error("[{}] Discharge message not sent. Message: {}", patchId, e.getMessage());
					LOG.debug("[{}] Discharge message not sent. Details: {}:", patchId, e);
				}
			}
        }   
    }
	
	public void enableDataDump(boolean flag) {
		enableDataDump = flag;
	}

	public boolean getDataDumpFlag() {
		return enableDataDump;
	}
	
	public String getPatchId() {
		return patchId;
	}

	public void setPatchId(String patchId) {
		this.patchId = patchId;
	}
	
	public String getFacilityId() {
		return facilityId;
	}
	
	public void setFacilityId(String facilityId) {
		this.facilityId = facilityId;
	}

	public long getPrevMsgCounter() {
		return prevMsgCounter;
	}

	public void setPrevMsgCounter(long prevMsgCounter) {
		this.prevMsgCounter = prevMsgCounter;
	}

	public long getMsgCounter() {
		return msgCounter;
	}

	public void setMsgCounter(long msgCounter) {
		this.msgCounter = msgCounter;
	}

	public long getLastRcvd() {
		return lastRcvd;
	}

	public void setLastRcvd(long lastRcvd) {
		this.lastRcvd = lastRcvd;
	}

	public String getPatientId() {
		return patientId;
	}

	public void setPatientId(String patientId) {
		this.patientId = patientId;
	}
	
	public void setServiceId(String serviceId) {
		this.serviceId = serviceId;
	}
	
	public void setProviderId(String providerId) {
		this.providerId = providerId;
	}
	
	public long getLastStreamed() {
		return lastStreamed;
	}
	
	public boolean socketStatus() {
		return socket.connected();
	}
}
