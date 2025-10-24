package com.lsmed.connectors.wsReceiver;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class WSThreadManager {

	public ThreadGroup wsReceivers;

	private static WSThreadManager theManager = null;

	private static final Logger LOG = LogManager.getLogger(WSThreadManager.class.getSimpleName());
	
	private WSThreadManager () {
	
		wsReceivers = new ThreadGroup ("wsReceivers");
	}
	
	public static WSThreadManager getInstance() {
		
		if (theManager != null)
			return theManager;
		
		theManager = new WSThreadManager();
		return theManager;
	}
	
	public static List<String> getActivePatches() {
		WSThreadManager mgr = getInstance();
        Thread[] threads  = mgr.getThreads();
		
		List<String> activePatchIds = new ArrayList<>();

		for (Thread thread : threads) {
		    if (thread instanceof WSReceiverThread) { // Check if it's a WSReceiverThread
		        WSReceiverThread wsReceiverThread = (WSReceiverThread) thread; // Cast to WSReceiverThread
		        String patchId = wsReceiverThread.getPatchId(); // Call the getPatchId() method
		        activePatchIds.add(patchId);
		    }
		}
		
		return activePatchIds;
	}
	
	public static Map<String, Long> getLastReceived() {
		WSThreadManager mgr = getInstance();
        Thread[] threads  = mgr.getThreads();
		
        Map<String, Long> streamingData = new HashMap<>(); // Use a Map to store patchId and lastStreamed

        for (Thread thread : threads) {
            if (thread instanceof WSReceiverThread) {
                WSReceiverThread wsReceiverThread = (WSReceiverThread) thread;
                String patchId = wsReceiverThread.getPatchId();
                long lastStreamed = wsReceiverThread.getLastStreamed();
                streamingData.put(patchId, lastStreamed); // Use put() to add to the Map
            }
        }

        return streamingData; // Return the Map
	}
	
	public static int[] socketStatus() {
		WSThreadManager mgr = getInstance();
        Thread[] threads  = mgr.getThreads();
        int activeCount = 0, totalCount = 0;
	
        for (Thread thread : threads) {
            if (thread instanceof WSReceiverThread) {
                WSReceiverThread instance = (WSReceiverThread) thread;
                totalCount++;
                try {
	                if(instance.socketStatus()) {
	                	activeCount++;
	                }
                } catch (Exception e) {
                	
                }
            }
        }
        
        int[] countData = {activeCount, totalCount};

        return countData; 
	}
	
	public Thread[] getThreads() {
		
		Thread[] tList = new Thread[wsReceivers.activeCount()];
		wsReceivers.enumerate(tList, false);
		return tList;
	}

	public ThreadGroup getThreadGroup () {
		return wsReceivers;
	}
	
	public WSReceiverThread getThread (String id) {
		
		Thread[] tList = getThreads();
		WSReceiverThread rt = null;
		
		for (int idx=0; idx < tList.length; idx++) {
			
			Thread t = tList[idx];
			String name = t.getName();
			if (id.equals(name)) {

				if (t instanceof WSReceiverThread) {
					rt = (WSReceiverThread)t;
					return rt;
				}
			}
		}
		
		return rt;
	}
	
	public void stopThread (String id) {
	
		Thread[] tList = getThreads();
		
		boolean found = false;
		for (int idx=0; idx < tList.length; idx++) {
			
			Thread t = tList[idx];
			String name = t.getName();
			if (id.equals(name)) {
				
				t.interrupt();
				LOG.info("Thread [" + id + "] stopped");
				found = true;
				/* Not returning here right away. Scan further to see if multiple instances were started */
			}
		}

		if (!found)
			LOG.warn("Thread [" + id + "] not running. Request ignored.");
	}
	
	public void stopThreads() {
		
		Thread[] tList = getThreads();
		
		for (int idx=0; idx < tList.length; idx++) {
			
			Thread t = tList[idx];
			String name = t.getName();
			t.interrupt();
			LOG.info("Thread [" + name + "] stopped");
		}
	}
	
}
