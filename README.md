# Presense Relay for Apollo

A modified version of the Java-based data relay service, specifically configured for the Apollo facility. It ingests data from patient-worn patches, processes it, and forwards it to external analysis services.

📖 Description
This project is a specialized version of the Presense Relay service, adapted for the Apollo facility. It operates as a robust backend system that:
- Listens to MQTT topics for service start/stop commands for specific patches. **Note: The specific topics are `svc_start_apollo` for starting and `svc_action_apollo` for stopping.**
- Establishes WebSocket connections to a data streaming source for each active patch.
- Processes incoming JSON data payloads, which include patient vitals like ECG, SPO2, heart rate, and sensor status.
- Persists the status of each monitored patch, including the `admissionId`, into a local SQLite database for state management and recovery.
- Forwards processed data to external analysis services, such as a "Presense" prediction endpoint.

### Key Modifications for Apollo
This version deviates from the original project in the following ways:
1.  **Simplified Authentication and Data**: It does not use an API to fetch patient details or tokens. This is because the Apollo Ubivue instance is hosted by a separate entity (Lifesignals), which prevented the creation of direct integration APIs. Instead, the service:
    *   Uses credentials from the `master.properties` file to authenticate against the token endpoint.
    *   Injects hardcoded default values (e.g., "Unknown", -1) for patient metadata such as `pgroupName`, `patientName`, `age`, and `gender`, which were previously fetched via an API.
2.  **Database-Managed `admissionId`**: The `admissionId` is now a required field for constructing the `patientRef`. It is persisted to the local SQLite database and retrieved from there, rather than being fetched from an external API.
3.  **Apollo-Specific MQTT Topics**: The MQTT topics are hardcoded to `svc_start_apollo` and `svc_action_apollo`. This is a critical distinction from the generic `svc_start` and `svc_action` topics used in the original Presense Relay. The differing complexities and hosting environments (Lifesigns vs. Lifesignals) require two separate applications to be deployed. Using distinct topics allows both this Apollo-specific version and the original version to run on the same server without interfering with each other.

The application is built with Maven and is designed to be a long-running, resilient service, featuring automatic reconnection for both MQTT and WebSocket connections, as well as state recovery on restart.

✅ Prerequisites
Before you begin, ensure you have the following software installed on your system:
- Java Development Kit (JDK): Version 11 or higher.
- Apache Maven: Version 3.6 or higher.
- MQTT Broker: An active MQTT broker (e.g., Mosquitto, HiveMQ).
- Git: For cloning the repository.

🛠️ Installation and Setup
This is the most critical step for setting up the project. The application uses two separate properties files for configuration, which must be passed as system properties during startup.

1. Clone the Repository
```bash
git clone <your-repository-url>
cd data-receiver
```

2. Create Configuration Files
You will need to create two configuration files. You can place them anywhere on your filesystem, as you will be providing the full path to them when you run the application.

- `master.properties`: For system-wide settings that rarely change.
- `config.properties`: For instance-specific settings, like database paths.

3. Configure Settings
Based on an analysis of the source code, the following properties must be configured.

#### `master.properties`
This file should contain the URLs for the various services the application interacts with.
```properties
# =================================================
# MASTER CONFIGURATION (APOLLO)
# =================================================

# --- API & Service Endpoints ---
# WebSocket server URL for receiving patch data
server.url=ws://your-websocket-server-url:port

# Authentication service URL to get access tokens for WebSockets
auth.url=https://your-auth-service.com/api/auth

# --- WebSocket Credentials ---
# These credentials are used to obtain the authentication token for the WebSocket connection.
fallback.user=your_username
fallback.pass=your_password
```

#### `config.properties`
This file should contain settings specific to your local machine or deployment instance.
```properties
# =================================================
# INSTANCE-SPECIFIC CONFIGURATION
# =================================================

# --- MQTT Broker Configuration ---
# The full URL of your MQTT Broker.
mqtt.url=tcp://localhost:1883

# A unique client ID for this instance of the data-receiver.
# If left blank, a random UUID will be generated and saved back to this file.
mqtt.client.id=presense-relay-apollo-01

# --- SQLite Database Configuration ---
# IMPORTANT: This is the full, absolute path to your SQLite database file.
# The application will create the file if it does not exist.
#
# Example for Windows: db.url=jdbc:sqlite:C:/Users/YourUser/data/presense-relay-apollo.db
# Example for Linux/macOS: db.url=jdbc:sqlite:/home/youruser/data/presense-relay-apollo.db
db.url=jdbc:sqlite:C:/path/to/your/database/presense-relay-apollo.db

# --- Jetty Web Server ---
# Port for the internal Jetty server to listen on for status checks or other servlets.
jetty.server.port=8088

# --- General Application Settings ---
# A source identifier for this data relay instance.
source=presense-relay-apollo-01

# --- Testing & Debugging ---
# A boolean flag to determine whether to use a test URL for services.
use.test.url=false

# A boolean flag to enable or disable writing data to a file.
write.to.file=false

# Set to 'true' to enable detailed console logging.
# This sets the logToConsoleLevel system property to DEBUG, otherwise it's OFF.
log.to.console=true
```

### Testing and Debugging Features
The `master.properties` file provides several flags that are essential for testing and debugging:

-   **`log.to.console`**: Set this to `true` to enable verbose, real-time logging to the console. This is useful for monitoring the application's activity and diagnosing issues without needing to check log files.

-   **`use.test.url`**: Set this to `true` to switch the destination of the processed data from the production endpoint (`https://vitals.presense.icu/data`) to a staging endpoint (`https://staging-vitals.presense.icu/data`). This allows for end-to-end testing without affecting production data.

-   **`write.to.file`**: Set this to `true` to save the final processed JSON payloads to local files. Each file is named in the format `<patchId>_<timestamp>.json` and is saved in the application's root directory. This is invaluable for inspecting the exact data being sent to the API.

🚀 Building and Running

1. Build the Project
Navigate to the project's root directory (where `pom.xml` is located) and run the following Maven command. This will compile the code, run tests, and package the application into a single, executable JAR file in the `target/` directory.
```bash
mvn clean package
```

2. Run the Application
Once the build is successful, run the service using the `java -jar` command. You must provide the paths to your configuration files using `-D` system properties.

Replace the placeholder paths with the actual absolute paths to your `master.properties` and `config.properties` files.
```bash
java -Dmaster.file.path="C:/path/to/your/master.properties" \
     -Dconfig.file.path="C:/path/to/your/config.properties" \
     -jar target/data-receiver-0.0.1-SNAPSHOT.jar
```
**Note**: The final JAR filename may vary slightly. Use the name of the JAR file generated in your `target/` directory.

If the `-Dmaster.file.path` property is not provided, the application will attempt to load the master configuration from `configFiles/master.properties` within the project's root directory.

If the `-Dconfig.file.path` property is not provided, the application will attempt to load the instance configuration from `configFiles/config.properties` within the project's root directory.