package com.lsmed.connectors.wsReceiver;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.jetty.server.Server;
import org.eclipse.paho.mqttv5.client.IMqttClient;
import org.eclipse.paho.mqttv5.client.IMqttToken;
import org.eclipse.paho.mqttv5.client.MqttCallback;
import org.eclipse.paho.mqttv5.client.MqttClient;
import org.eclipse.paho.mqttv5.client.MqttConnectionOptions;
import org.eclipse.paho.mqttv5.client.MqttDisconnectResponse;
import org.eclipse.paho.mqttv5.client.persist.MemoryPersistence;
import org.eclipse.paho.mqttv5.common.MqttException;
import org.eclipse.paho.mqttv5.common.MqttMessage;
import org.eclipse.paho.mqttv5.common.packet.MqttProperties;
import org.json.JSONObject;
import com.lsmed.connectors.wsReceiver.server.JettyServer;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.FileInputStream;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLTransientException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class DataReceiver {

	private static final Logger LOG = LogManager.getLogger(DataReceiver.class.getSimpleName());

	public static boolean done = false;

	private static Server server;

	private static IMqttClient mqttClient = null;

	private static String dbUrl;
	private static String clientId;
	private static String mqttUrl; // Added for MQTT URL

	private static HikariDataSource dataSource;

	private static final ConcurrentHashMap<String, Map<String, String>> activePatches = new ConcurrentHashMap<>();
	private static final ConcurrentHashMap<String, String> admissionIdUpdates = new ConcurrentHashMap<>(); // <-- ADDED
	public static void main(String[] args) {

		ConfigurationManager configManager = ConfigurationManager.getInstance(); // Get the instance
		((org.apache.logging.log4j.core.LoggerContext) org.apache.logging.log4j.LogManager.getContext(false)).reconfigure();

		LOG.info("Data streaming to presense for Apollo Mysuru Patches starting... -- no ECG.");
		dbUrl = configManager.getDbUrl(); // Get from config manager
		clientId = configManager.getMqttClientId(); // Get from config manager
		mqttUrl = configManager.getProperty("mqtt.url"); //Get MQTT URL
		if (mqttUrl == null || mqttUrl.isEmpty()) {
			mqttUrl = "tcp://localhost:1883"; // default
			LOG.warn("MQTT URL is not configured.  Using default: " + mqttUrl);
		}
		dataSource = createHikariDataSource();
		createTable(dataSource);

		restart(dataSource);

		if (clientId == null || clientId.isEmpty()) {
			clientId = UUID.randomUUID().toString();
			storeClientId(clientId);
			LOG.info("Generated and stored new Client ID in config: " + clientId);
		} else {
			LOG.info("Loaded Client ID from config: " + clientId);
		}

		MqttConnectionOptions options = new MqttConnectionOptions();

		options.setAutomaticReconnect(true);
		options.setCleanStart(false);
		options.setConnectionTimeout(30);

		// options.setUserName("Arun");
		// options.setPassword(new String("1234").getBytes());

		try {

			LOG.info("MQTT Server: " + mqttUrl);
			mqttClient = new MqttClient(mqttUrl, clientId, new MemoryPersistence());
			mqttClient.connect(options);
		} catch (MqttException me) {
			LOG.error("Failed to connect to MQTT Server. Reason Code: {}, Message: {}", me.getReasonCode(), me.getMessage());
			LOG.debug("MQTT Connection Exception Details:", me);
			return;
		}

		boolean status = mqttClient.isConnected();
		LOG.info("MQTT Server Status: " + status);

		mqttClient.setCallback(new MqttCallback() {

			// Called when the client lost the connection to the broker
			@SuppressWarnings("unused")
			public void connectionLost(Throwable cause) {
				LOG.fatal("Client lost connection. Message: {}", cause.getMessage());
				LOG.debug("Client lost connection Details:", cause);
			}

			@Override
			public void connectComplete(boolean reconnect, String serverURI) {
				if (reconnect) {
					LOG.info("Connection revived (persistent session resumed) to: " + serverURI);
					// ... any actions to take after a successful reconnection
				} else {
					LOG.info("Fresh connection established to: " + serverURI);
					// ... any actions to take after a new connection
				}
			}

			public void messageArrived(String topic, MqttMessage message) {

				String payload = new String(message.getPayload());

				LOG.info("topic: " + topic + ", QoS: " + message.getQos());
				LOG.info("message content: " + payload);

				if ("arrhythmia/svc_start_apollo".equalsIgnoreCase(topic)) {

					/*
					 * Specific service is requested for the patchId e.g. Arrhythmia. Service
					 * Provider may also be specified if account specific providers are configured.
					 *
					 * Requesting entity should supply all required data.
					 */
					JSONObject json = new JSONObject(payload);
					String patchId = json.has("patchId") ? json.getString("patchId") : null; /* Mandatory */
					String facilityId = json.has("facilityId") ? json.getString("facilityId") : null; /* Mandatory */
					String serviceId = json.has("serviceId") ? json.getString("serviceId") : null; /* Mandatory */
					String providerId = json.has("providerId") ? json.getString("providerId") : null; /* Optional */
					String admissionId = json.has("admissionId") ? json.getString("admissionId") : null; // <-- MODIFIED
					String patientId = null; /* Mandatory */
					try {
						patientId = json.has("patientId") ? json.getString("patientId") : null;
					} catch (Exception e) {
						patientId = json.has("patientId") ? String.valueOf(json.getInt("patientId")) : null;
					}

					if ((patchId == null) || (serviceId == null)) {

						LOG.error("svc_start: Incorrect parameters. patchId: [" + patchId + "], serviceId: ["
								+ serviceId + "]. Request ignored.");
						return;
					}

					try {
						if (addBiosensor(patchId, patientId, serviceId, providerId, facilityId, admissionId)) {
    						WSReceiverThread t = new WSReceiverThread(facilityId, patchId, patientId, serviceId, providerId, 0, admissionId);
							t.start();
							Map<String, String> patchDetails = new HashMap<>();
							patchDetails.put("facilityId", facilityId);
							patchDetails.put("patientId", patientId);
							patchDetails.put("serviceId", serviceId);
							patchDetails.put("providerId", providerId);
							patchDetails.put("instanceId", clientId);
							patchDetails.put("admissionId", admissionId);
							activePatches.put(patchId, patchDetails);
						}

					} catch (Exception e) {
						LOG.error("Failed to start for Patch: " + patchId);
						LOG.error("Failed to start for Patch: {}, Message: {}", patchId, e.getMessage());
						LOG.debug("Failed to start for Patch: {}, Details: {}", patchId, e);
					}

				} else if ("arrhythmia/svc_action_apollo".equalsIgnoreCase(topic)) {

					JSONObject json = new JSONObject(payload);
					String patchId = json.getString("patchId");
					String action = json.getString("action");
					if ("stop".equals(action)) {

						WSThreadManager mgr = WSThreadManager.getInstance();
						WSReceiverThread rt = mgr.getThread(patchId);
						rt.Stop = true;
						mgr.stopThread(patchId);
						stopBiosensor(patchId);

						activePatches.remove(patchId);

					} else if ("debug".equals(action)) {

						boolean flag = json.has("enableDataDump") ? json.getBoolean("enableDataDump") : false;
						WSThreadManager mgr = WSThreadManager.getInstance();
						if (patchId.compareTo("ALL") == 0) {
							List<String> runningPatches = WSThreadManager.getActivePatches();

							for (String patch : runningPatches) {
								WSReceiverThread rt = mgr.getThread(patch);
								if (rt != null) {
									LOG.info("PatchId: " + patch + ". Setting enableDataDump flag: " + flag);
									rt.enableDataDump(flag);
								} else {
									LOG.info("Receiver not running for: " + patchId + ". Flag enableDataDump ignored.");
								}
							}
						} else {
							WSReceiverThread rt = mgr.getThread(patchId);
							if (rt != null) {
								LOG.info("PatchId: " + patchId + ". Setting enableDataDump flag: " + flag);
								rt.enableDataDump(flag);
							} else {
								LOG.info("Receiver not running for: " + patchId + ". Flag enableDataDump ignored.");
							}
						}
					}
				} else {
					LOG.warn("Unknown Topic: " + topic + ". Ignored.");
				}
			}

			/**
			 * Called when an outgoing publish is complete
			 *
			 * @param token
			 */

			@Override
			public void disconnected(MqttDisconnectResponse e) {
				LOG.error("Disconnected from MQTT. Reason Code: {}, Message: {}", e.getReturnCode(), e.getReasonString());
				LOG.debug("Disconnected from MQTT. Details:", e);
				// Handle disconnection (reconnect logic is already in place)
			}

			@Override
			public void mqttErrorOccurred(MqttException e) {
				LOG.error("MQTT error occurred. Reason Code: {}, Message: {}", e.getReasonCode(), e.getMessage());
				LOG.debug("MQTT error occurred Details:", e);
				// ... (reconnection logic - already implemented)
			}

			@Override
			public void deliveryComplete(IMqttToken token) {
				LOG.debug("Delivery complete " + token);

			}

			@Override
			public void authPacketArrived(int reasonCode, MqttProperties properties) {
				// TODO Auto-generated method stub

			}
		});

		try {

			mqttClient.subscribe("$share/svc_start_group/arrhythmia/svc_start_apollo", 2);
			mqttClient.subscribe("arrhythmia/svc_action_apollo", 2);
			LOG.info("Subscription done.");

		} catch (MqttException e) {
			LOG.fatal("Failed to subscribe to topics of interest. Reason Code: {}, Message: {}", e.getReasonCode(), e.getMessage());
			LOG.debug("Failed to subscribe to topics of interest. Details:", e);
			cleanup();
			return;
		}

		/* Start Jetty */
		JettyServer server = new JettyServer();
		try {
			LOG.info("Starting WebServer");
			server.start();
		} catch (Exception e) {
			LOG.fatal("Failed to start Web Services. Message: {}", e.getMessage());
			LOG.debug("Failed to start Web Services. Details:", e);
			cleanup();
			return;
		}

		while (!done) {

			try {
				Thread.sleep(60000);
				LOG.debug("Starting Housekeeping");
				Map<String, Long> lastReceivedMap = WSThreadManager.getLastReceived();
				updateLastStreamedTimes(lastReceivedMap, dataSource);
				updateAdmissionIdsInDb(dataSource);
				checkAndRestartInactivePatches(dataSource); // New method
				int[] socketCount = WSThreadManager.socketStatus();
				LOG.info("{}/{} patches have active socket connection", socketCount[0], socketCount[1]);

			} catch (InterruptedException e) {

				// Someone requesting to stop everything
				LOG.debug("Main thread interrupted. Initiating cleanup");
				break;
			}
		}

	}

	public static void requestAdmissionIdUpdate(String patchId, String admissionId) {
		if (patchId != null && admissionId != null) {
			admissionIdUpdates.put(patchId, admissionId);
		}
	}

	private static HikariDataSource createHikariDataSource() {
		HikariConfig config = new HikariConfig();
		config.setJdbcUrl(dbUrl);

		// Connection Pool Configuration (Crucial)
		config.setMaximumPoolSize(20); // Adjust based on your needs
		config.setMinimumIdle(5); // Keep at least this many connections open
		config.setIdleTimeout(300000); // 5 minutes (connections idle for this time are closed)
		config.setMaxLifetime(600000); // 10 minutes (connections older than this are closed)
		config.setConnectionTimeout(60000); // 60 seconds (time to wait for a connection)
		config.setLeakDetectionThreshold(120000); // 2 minute (detect connection leaks)

		return new HikariDataSource(config);
	}

	private static void createTable(HikariDataSource dataSource) {
		int maxRetries = 3;
		int retryDelay = 1000; // 1 second

		for (int attempt = 1; attempt <= maxRetries; attempt++) {
			try (Connection dbConnection = dataSource.getConnection();
				 Statement statement = dbConnection.createStatement()) {

				// SQL statement to create the table
				String createTableSQL = "CREATE TABLE IF NOT EXISTS biosensor_status (" +
						"  id INTEGER PRIMARY KEY AUTOINCREMENT, " +
						"  patch_id TEXT NOT NULL, " +
						"  status TEXT NOT NULL, " +
						"  last_streamed_time DATETIME, " +
						"  patient_id TEXT NOT NULL, " +
						"  start_time DATETIME, " +
						"  last_updated DATETIME, " +
						"  service_id TEXT NOT NULL, " +
						"  provider_id TEXT NOT NULL, " +
						"  facility_id TEXT, " +
						"  instance_id TEXT  " +
						"  admission_id TEXT" + 
						");";
				statement.execute(createTableSQL);
				break; // If the operation is successful, exit the loop

			} catch (SQLTransientException e) {
				LOG.warn("Transient SQL exception (attempt {}): {}", attempt, e.getMessage());
				if (attempt < maxRetries) {
					try {
						Thread.sleep(retryDelay);
					} catch (InterruptedException ex) {
						Thread.currentThread().interrupt(); // Restore interrupt status
						break; // Exit the retry loop if interrupted
					}
				} else {
					LOG.fatal("CreateTable operation failed after {} retries: ", maxRetries, e.getMessage());
					LOG.debug("CreateTable operation failed after {} retries. Details", maxRetries, e);
				}
			} catch (SQLException e) {
				LOG.fatal("Failed to create table: Message: {}", e.getMessage());
				LOG.debug("Failed to create table: Details: {}", e);
				break;
			}
		}
	}


	private static void storeClientId(String clientId) {
		String configFilePath = System.getProperty("config.file.path");
		if (configFilePath == null) {
			configFilePath = "config.properties"; // Default if not specified
		}
		try {
			Properties properties = new Properties();
			File configFile = new File(configFilePath);
			if (configFile.exists()) {
				try (FileInputStream input = new FileInputStream(configFile)) {
					properties.load(input);
				}
			}
			properties.setProperty("mqtt.client.id", clientId);
			try (FileOutputStream output = new FileOutputStream(configFile)) {
				properties.store(output, "MQTT Configuration");
			}
		} catch (IOException e) {
			LOG.error("Error storing client ID in config. Message: {}", e.getMessage());
			LOG.debug("Error storing client ID in config. Details:", e);
		}
	}

	private static void restart(HikariDataSource dataSource) {
		String selectAllPatchesSQL = "SELECT patch_id, service_id, patient_id, facility_id, provider_id, admission_id FROM biosensor_status WHERE status != 'Stopped' AND instance_id = ?";

		LOG.info("Restarting running patches for instance: " + clientId);

		try (Connection dbConnection = dataSource.getConnection();
			 PreparedStatement selectStatement = dbConnection.prepareStatement(selectAllPatchesSQL)) {
			selectStatement.setString(1, clientId);
			try (ResultSet resultSet = selectStatement.executeQuery()) {

				while (resultSet.next()) {
					String patchId = resultSet.getString("patch_id");
					String serviceId = resultSet.getString("service_id");
					String patientId = resultSet.getString("patient_id");
					String facilityId = resultSet.getString("facility_id");
					String providerId = resultSet.getString("provider_id");
					String admissionId = resultSet.getString("admission_id");
					Map<String, String> patchDetails = new HashMap<>();
					patchDetails.put("facilityId", facilityId);
					patchDetails.put("patientId", patientId);
					patchDetails.put("serviceId", serviceId);
					patchDetails.put("providerId", providerId);
					patchDetails.put("instanceId", clientId);
					patchDetails.put("admissionId", admissionId); 

					activePatches.put(patchId, patchDetails);
				}
			}
		} catch (SQLException e) {
			LOG.error("Failed to connect to Database on restart. Message: {}", e.getMessage());
			LOG.debug("Failed to connect to Database on restart. Details:", e);
			return;
		}

		for (Map.Entry<String, Map<String, String>> entry : activePatches.entrySet()) {
			String patchId = entry.getKey();
			Map<String, String> patchDetails = entry.getValue();

//            LOG.warn("Patch [{}] restarting.", patchId);
			try {
				WSReceiverThread t = new WSReceiverThread(patchDetails.get("facilityId"), patchId, patchDetails.get("patientId"), patchDetails.get("serviceId"), patchDetails.get("providerId"), 0, patchDetails.get("admissionId"));
				t.start();

			} catch (Exception e) {
				LOG.error("Failed to restart patch {}. Message: {}", patchId, e.getMessage());
				LOG.debug("Failed to restart patch {}. Details: {}", patchId, e);
			}

		}

	}

	private static void checkAndRestartInactivePatches(HikariDataSource dataSource) {
		List<String> runningPatches = WSThreadManager.getActivePatches(); // Get running patches from WSThreadManager
		List<String> stoppedPatches = new ArrayList<String>();
		int flag = 0;

		for (Map.Entry<String, Map<String, String>> entry : activePatches.entrySet()) {
			String patchId = entry.getKey();
			Map<String, String> patchDetails = entry.getValue();


			if (!runningPatches.contains(patchId) && entry.getValue().get("instanceId").equals(clientId)) {
				flag++;
				stoppedPatches.add(patchId);
				LOG.warn("Patch {} appears to be inactive. Restarting.", patchId);
				try {
					WSReceiverThread t = new WSReceiverThread(patchDetails.get("facilityId"), patchId, patchDetails.get("patientId"), patchDetails.get("serviceId"), patchDetails.get("providerId"), 0, patchDetails.get("admissionId"));
					t.start();

				} catch (Exception e) {
					LOG.error("Failed to restart patch {}. Message: {}", patchId, e.getMessage());
					LOG.debug("Failed to restart patch {}. Details: {}", patchId, e);
				}
			}
		}

		if (flag == 0) {
			LOG.info("All patches running!! ", runningPatches);
		} else {
			LOG.warn("The following patches had stopped: ", stoppedPatches);
		}
	}

	private static void cleanup() {

		/*
		 * Cleanup MQTT.
		 */
		if (mqttClient != null) {

			try {
				mqttClient.close();
			} catch (Exception e) {
			}
		}

		/*
		 * Cleanup all WebSockets.
		 */
		WSThreadManager mgr = WSThreadManager.getInstance();
		mgr.stopThreads();
	}

	public static Server getServer() {
		return server;
	}

	public static void setServer(Server server) {
		DataReceiver.server = server;
	}

	private static boolean addBiosensor(String patchId, String patientId, String serviceId, String providerId, String facilityId, String admissionId) {
		int maxRetries = 3;
		int retryDelay = 1000; // 1 second

		for (int attempt = 1; attempt <= maxRetries; attempt++) {
			try (Connection dbConnection = dataSource.getConnection();
				 PreparedStatement checkStatement = dbConnection.prepareStatement("SELECT 1 FROM biosensor_status WHERE patch_id = ?");
				 PreparedStatement insertStatement = dbConnection.prepareStatement(
						 "INSERT INTO biosensor_status (patch_id, patient_id, service_id, provider_id, status, start_time, facility_id, instance_id, admission_id) " + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)")) {

				// Check if patch_id already exists
				checkStatement.setString(1, patchId);
				ResultSet resultSet = checkStatement.executeQuery();

				if (resultSet.next()) {
					LOG.warn("addBiosensor: patch_id {} already exists. Skipping insertion.", patchId);
					return false; // Patch ID already exists
				}

				// Patch ID does not exist, proceed with insertion
				insertStatement.setString(1, patchId);
				insertStatement.setString(2, patientId);
				insertStatement.setString(3, serviceId);
				insertStatement.setString(4, providerId);
				insertStatement.setString(5, "running");
				insertStatement.setString(6, new Timestamp(Instant.now().getEpochSecond() * 1000).toString());
				insertStatement.setString(7, facilityId);
				insertStatement.setString(8, clientId);
				insertStatement.setString(9, admissionId);
				insertStatement.executeUpdate();
				return true; // Insertion successful

			} catch (SQLTransientException e) {
				LOG.warn("addBiosensor: Transient SQL exception for patch {} (attempt {}): {}", patchId, attempt, e.getMessage());
				if (attempt < maxRetries) {
					try {
						Thread.sleep(retryDelay);
					} catch (InterruptedException ex) {
						Thread.currentThread().interrupt(); // Restore interrupt status
						return false; // Exit on interrupt
					}
				} else {
					LOG.error("addBiosensor operation failed for {} after {} retries: {}", patchId, maxRetries, e.getMessage());
					LOG.debug("addBiosensor operation failed for {} after {} retries. Details: ", patchId, maxRetries, e);
					return false; // Failed after retries
				}

			} catch (SQLException e) {
				LOG.error("Failed to addBiosensor in database for patchId: {}.  Error: {}, SQLState: {}, ErrorCode: {}",
						patchId, e.getMessage(), e.getSQLState(), e.getErrorCode());
				LOG.debug("Stack Trace for addBiosensor: ", e);
				return false; // SQL Exception
			}
		}
		return false; // Should not reach here, but added for safety.
	}

	private static void stopBiosensor(String patchId) {
		String checkPatchIdSQL = "SELECT 1 FROM biosensor_status WHERE patch_id = ?";

		int maxRetries = 3;
		int retryDelay = 1000; // 1 second

		for (int attempt = 1; attempt <= maxRetries; attempt++) {
			try (Connection dbConnection = dataSource.getConnection();
				 PreparedStatement statement = dbConnection.prepareStatement(
						 "INSERT INTO biosensor_status (patch_id, status) " + "VALUES (?, ?)");
				 PreparedStatement checkStatement = dbConnection.prepareStatement(checkPatchIdSQL)) {

				// Check if patch_id already exists
				checkStatement.setString(1, patchId);
				try (ResultSet resultSet = checkStatement.executeQuery()) {
					if (resultSet.next()) {
						try (PreparedStatement updateStatement = dbConnection.prepareStatement(
								"UPDATE biosensor_status SET status = ?, last_updated = ? " +
										"WHERE patch_id = ?")) {

							updateStatement.setString(1, "Stopped");
							updateStatement.setString(2, new Timestamp(Instant.now().getEpochSecond() * 1000).toString());
							updateStatement.setString(3, patchId);
							updateStatement.executeUpdate();

						} catch (SQLException e) {
							LOG.error("Failed to write stop for patchId: {}.  Error: {}, SQLState: {}, ErrorCode: {}",
									patchId, e.getMessage(), e.getSQLState(), e.getErrorCode());
							LOG.debug("Failed to write stop for patchId: {}. Detais: {} ", patchId, e);
						}
					} else {
						LOG.warn("PatchId {} does not exist in the db");
					}
					break;
				}
			} catch (SQLTransientException e) {
				LOG.warn("Transient SQL exception for {} (attempt {}): {}", patchId, attempt, e.getMessage());
				if (attempt < maxRetries) {
					try {
						Thread.sleep(retryDelay);
					} catch (InterruptedException ex) {
						Thread.currentThread().interrupt(); // Restore interrupt status
						break; // Exit the retry loop if interrupted
					}
				} else {
					LOG.error("stopBiosensor operation failed after {} retries: {}", maxRetries, e.getMessage());
					LOG.debug("stopBiosensor operation failed after {} retries. Details: ", maxRetries, e);
				}
			} catch (SQLException e) {
				LOG.error("Failed to write stop status in database for patchId: {}.  Error: {}, SQLState: {}, ErrorCode: {}",
						patchId, e.getMessage(), e.getSQLState(), e.getErrorCode());
				LOG.debug("Stack Trace for stopBiosensor: ", e);
				break;
			}
		}
	}

	private static void updateLastStreamedTimes(Map<String, Long> lastStreamedMap, HikariDataSource dataSource) {
		if (lastStreamedMap == null || lastStreamedMap.isEmpty()) {
			LOG.debug("No last received times to update.");
			return;
		}

		int maxRetries = 3;
		int retryDelay = 1000;

		for (Map.Entry<String, Long> entry : lastStreamedMap.entrySet()) {
			String patchId = entry.getKey();
			Long lastStreamedTime = entry.getValue();

			if (lastStreamedTime == null || lastStreamedTime == 0) {
//                LOG.debug("No last received time to update for patchId: {}", patchId);
				continue; // Skip if no time to update
			}

			for (int attempt = 1; attempt <= maxRetries; attempt++) {
				try (Connection dbConnection = dataSource.getConnection();
					 PreparedStatement statement = dbConnection.prepareStatement(
							 "UPDATE biosensor_status SET last_streamed_time = ?, last_updated = ? WHERE patch_id = ?")) {

					statement.setString(1, new Timestamp(lastStreamedTime * 1000).toString());
					statement.setString(2, new Timestamp(Instant.now().getEpochSecond() * 1000).toString());
					statement.setString(3, patchId);
					int rowsUpdated = statement.executeUpdate();

					if (rowsUpdated > 0) {
//                        LOG.debug("Updated last_streamed_time for patchId: {}", patchId);
						break; // Success, exit retry loop
					} else {
						LOG.warn("No row updated for patchId: {}.  Possibly patch_id not in DB", patchId);
						break; // If no row updated, patch likely not in DB, so no need to retry
					}

				} catch (SQLTransientException e) {
					LOG.warn("updateLastStreamedTimes: Transient SQL exception for patch {} (attempt {}): {}", patchId, attempt, e.getMessage());
					if (attempt < maxRetries) {
						try {
							Thread.sleep(retryDelay);
						} catch (InterruptedException ex) {
							Thread.currentThread().interrupt();
							break;
						}
					} else {
						LOG.error("updateLastStreamedTimes operation failed for {} after {} retries: {}", patchId, maxRetries, e.getMessage());
						LOG.debug("updateLastStreamedTimes operation failed for {} after {} retries. Details: ", patchId, maxRetries, e);
					}
				} catch (SQLException e) {
					LOG.error("Failed to update last_streamed_time in database for patchId: {}. Error: {}, SQLState: {}, ErrorCode: {}",
							patchId, e.getMessage(), e.getSQLState(), e.getErrorCode());
					LOG.debug("Stack Trace for updateLastStreamedTimes: ", e);
					break; // Exit retry loop on SQLException
				}
			}
		}
	}

	// <-- ADDED THIS ENTIRE METHOD
	private static void updateAdmissionIdsInDb(HikariDataSource dataSource) {
		if (admissionIdUpdates.isEmpty()) {
			return;
		}

		String sql = "UPDATE biosensor_status SET admission_id = ? WHERE patch_id = ?";

		for (Map.Entry<String, String> entry : admissionIdUpdates.entrySet()) {
			String patchId = entry.getKey();
			String admissionId = entry.getValue();

			try (Connection dbConnection = dataSource.getConnection();
				 PreparedStatement statement = dbConnection.prepareStatement(sql)) {

				statement.setString(1, admissionId);
				statement.setString(2, patchId);
				int rowsUpdated = statement.executeUpdate();

				if (rowsUpdated > 0) {
					LOG.info("Persisted admissionId for patch: {}", patchId);
					admissionIdUpdates.remove(patchId);
				}
			} catch (SQLException e) {
				LOG.error("Failed to update admission_id in DB for {}. Error: {}", patchId, e.getMessage());
			}
		}
	}
}