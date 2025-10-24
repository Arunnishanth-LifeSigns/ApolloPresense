package com.lsmed.connectors.wsReceiver;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class ApiService {

    private static final Logger LOG = LogManager.getLogger(ApiService.class);
    private static final HttpClient httpClient = HttpClient.newHttpClient();

    public JsonNode getPatchInfo(String patchId, String facilityId) {
//        String baseUrl = "https://dev-apps.lifesigns.us/prediction/api/pgroup-name?";
        ConfigurationManager configManager = ConfigurationManager.getInstance();
        String baseUrl = configManager.getProperty("details.api");
        String url = baseUrl + "biosensorId=" + patchId; //+ "&facilityId=" + (facilityId);

        try {
            HttpRequest request = HttpRequest.newBuilder().uri(URI.create(url)).build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                ObjectMapper objectMapper = new ObjectMapper();
                return objectMapper.readTree(response.body());
            } else {
                LOG.error("API request failed with status code: {}", response.statusCode());
                LOG.error("API Response: {}", response.body());
                return null;
            }
        } catch (InterruptedException e) {
            LOG.error("API request interrupted: {}", e.getMessage(), e); // Log the exception
            Thread.currentThread().interrupt(); // Re-interrupt the thread
            return null; 
        } catch (Exception e) {
            LOG.error("An unexpected error has occured: {}", e.getMessage(), e);
            return null;
        }
    }
}
