package com.lsmed.connectors.wsReceiver;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.ProtocolException;
import java.net.URI;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.apache.hc.client5.http.entity.UrlEncodedFormEntity;
import org.apache.hc.client5.http.fluent.Content;
import org.apache.hc.client5.http.fluent.Request;
import org.apache.hc.core5.http.NameValuePair;
import org.apache.hc.core5.http.message.BasicNameValuePair;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.json.JSONObject;

public class AuthHelper {

	/**
	 * LOG
	 */
	private static final Logger LOG = LogManager.getLogger(AuthHelper.class.getSimpleName());

	private static ConfigurationManager configManager = ConfigurationManager.getInstance(); // Get the instance once
	public static String authUrl = configManager.getAuthUrl();

	public static String auth(String patchId, String facilityId) {
		
		String token = null;
		int tokenResponseCode = 0;
		ConfigurationManager configManager = ConfigurationManager.getInstance();
		String api = configManager.getProperty("cred.api") + facilityId;
//		String api = "https://dev-apps.lifesigns.us/prediction/api/credentials/" + facilityId;
		
		int MAX_RETRIES = 5;
		double BASE_DELAY_MS = 1000;
		int attempt = 0;
		while (true) {		
			
			URI uri = URI.create(api);
	        URL url = null;
			try {
				url = uri.toURL();
			} catch (MalformedURLException e) {
				LOG.error("Failed to convert URL: ", patchId);
				return token;
			}
			
	        HttpURLConnection conn = null;
			try {
				conn = (HttpURLConnection) url.openConnection();
			} catch (IOException e) {
				LOG.error("Failed to establish HTTP connection: ", patchId);
				return token;
			}
			
	        try {
				conn.setRequestMethod("GET");
			} catch (ProtocolException e) {
				LOG.error("Failed to set request method: ", patchId);
				return token;
			}

			try {
				tokenResponseCode = conn.getResponseCode();
			} catch (IOException e) {
				LOG.error("Failed to get Token Response Code: ", patchId);
				return token;
			}
	 	
			StringBuilder response = new StringBuilder();
			if (tokenResponseCode == HttpURLConnection.HTTP_OK) {
			    try (BufferedReader in = new BufferedReader(new InputStreamReader(conn.getInputStream())))
			    {
			        String inputLine;
			        while ((inputLine = in.readLine()) != null) {
			            response.append(inputLine);
			        }
			    } catch (IOException e) {
			    	LOG.error("Failed to open Buffered reader: ", patchId);
			    	return token;
			    }
			    
			    String[] credentials = response.substring(1, response.length() - 1).toString().split(":", 2); 
				String username = credentials[0].trim().substring(1, credentials[0].trim().length() - 1);
				String password = credentials[1].trim().substring(1, credentials[1].trim().length() - 1);
				
				if (credentials.length != 2) {
					LOG.fatal("Error: Invalid response format in API while fetching credentials.");
				} else {
					token = getToken(username, password);
				}
			    
			} else {
				LOG.warn("Request to get credentials failed. HTTP Error code: " + tokenResponseCode);
			}
			
			if(token != null) {
				return token;
			} else if (Math.floor(tokenResponseCode/100)  == 5) {
				attempt++;
				if (attempt > MAX_RETRIES) {
					LOG.warn("Didn't get a token for [" + patchId + "]");
					return token;
				}
				long delay = (long) (BASE_DELAY_MS * Math.pow(2, attempt));

				LOG.debug(patchId + " " + attempt + "th failure");

				try {
					TimeUnit.MILLISECONDS.sleep(delay);
				} catch (InterruptedException ex) {
					Thread.currentThread().interrupt();
				}
			} else {
				LOG.warn("Didn't get a token for [" + patchId + "]");
				return token;
			}
		}
	}
	
	public static String getToken (String username, String password) {
    
		String token = null;

		try {
			// 1. Create a list of parameters for the form
			List<NameValuePair> form = new ArrayList<>();
			form.add(new BasicNameValuePair("grant_type", "password"));
			form.add(new BasicNameValuePair("client_id", "InfinityEP4WebUI")); // As discovered in our curl test
			form.add(new BasicNameValuePair("username", username));
			form.add(new BasicNameValuePair("password", password));

			// 2. Create the URL-encoded entity from the form data
			UrlEncodedFormEntity entity = new UrlEncodedFormEntity(form);

			// 3. Build the request, no need to manually set Content-Type
			Request request = Request.post(authUrl)
					.body(entity); // Setting the body automatically sets the correct Content-Type

			// 4. Execute the request
			Content content = request.execute().returnContent();
			String s = content.asString();
			
			// This part seems to have an error in your original code. 
			// The token is usually at the top level of the JSON response.
			JSONObject json = new JSONObject(s);
			token = json.getString("access_token");
			LOG.info("Token Obtained");
			
		} catch (Exception e) {
			// Using LOG.error is better than System.out.println for consistency
			LOG.error("Failed to get token", e);
		}
		return token;
	}
}
