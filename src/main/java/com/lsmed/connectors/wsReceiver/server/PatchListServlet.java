package com.lsmed.connectors.wsReceiver.server;

import java.io.IOException;

import org.json.JSONArray;
import org.json.JSONObject;

import com.lsmed.connectors.wsReceiver.WSReceiverThread;
import com.lsmed.connectors.wsReceiver.WSThreadManager;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

public class PatchListServlet extends HttpServlet {

    /**
	 * 
	 */
	private static final long serialVersionUID = 5175107649895486676L;

	protected void doGet(HttpServletRequest request, HttpServletResponse response) 
			throws ServletException, IOException {
    	
		WSThreadManager mgr = WSThreadManager.getInstance();
		Thread[] tList = mgr.getThreads();
		
		JSONArray patchList = new JSONArray();
		
		for (int idx=0; idx < tList.length; idx++) {
			
			Thread t = tList[idx];
			if (t instanceof WSReceiverThread) {
				
				WSReceiverThread rt = (WSReceiverThread)t;
				String patchId = rt.getPatchId();
				long msgCounter = rt.getMsgCounter();
				long prevCounter = rt.getPrevMsgCounter();
				long lastReceived = rt.getLastRcvd();

				/* Send data points for each connected socket */
				JSONObject obj = new JSONObject();
				obj.put("patchId", patchId);
				obj.put("msgCounter", msgCounter);
				obj.put("prevCounter", prevCounter);
				obj.put("lastReceived", lastReceived);
				
				patchList.put(obj);
			}
		}
		
        response.setContentType("application/json");
        response.setStatus(HttpServletResponse.SC_OK);
        response.getWriter().println(patchList.toString());
    }
}