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

#### `master.properties` (Common Configuration)

This file contains the main, shared configuration for all application instances.

```properties
# =================================================
# MASTER CONFIGURATION (COMMON)
# =================================================

# --- API & Service Endpoints ---
# WebSocket server URL for receiving patch data
server.url=wss://lifesignals.co.in

# Authentication service URL to get access tokens for WebSockets
auth.url=https://dev2a.auth.lifesignals.com/auth/realms/lsdev/protocol/openid-connect/token

# API endpoint to fetch credentials for a specific facility.
# The application appends the facilityId to this URL.
cred.api = https://dev-apps.lifesigns.us/prediction/api/credentials/

# API endpoint to fetch patch and patient details.
# The application appends "?biosensorId=<patchId>" to this URL.
details.api = https://dev-apps.lifesigns.us/prediction/api/pgroup-name?

# --- Fallback Credentials ---
# These are used by the WebSocket receiver if the credentials.api fails.
fallback.user=sys.user.sc.apo_mys@lifesigns.us
fallback.pass=LsSys*%@#usr

# --- MQTT Broker Configuration ---
# The full URL of your MQTT Broker (shared by all instances).
mqtt.url=tcp://localhost:1883

# --- SQLite Database Configuration ---
# IMPORTANT: This is the full, absolute path to your SQLite database file.
db.url=jdbc:sqlite:Apollo.db

# --- General Application & Debugging Settings ---
# A source identifier for this data relay instance.
source=Arun-Local

# Set to 'true' to use a staging/development endpoint instead of production.
use.test.url=true

# Set to 'true' to save processed JSON payloads to local files for inspection.
write.to.file=false

# Set to 'true' to enable verbose, real-time logging to the console.
log.to.console=true
```

#### `config.properties` (Instance-Specific Configuration)

This file contains settings that must be unique for each running instance of the application.

```properties
# =================================================
# INSTANCE-SPECIFIC CONFIGURATION
# =================================================

# --- Jetty Web Server ---
# A unique port for the internal Jetty server to listen on.
# Each instance of the application MUST have a different port.
jetty.server.port=8093

# --- MQTT Broker Configuration ---
# A unique client ID for this instance of the data-receiver.
# If left blank, a random UUID will be generated and saved back to this file.
mqtt.client.id=c50a4771-ca4e-4967-9d6c-2683f8304893234
```

### Configuration Files

The application's configuration is split between two files. This approach is necessary to allow for the deployment of multiple application instances, as a single instance is unable to handle more than 50-60 simultaneous WebSocket connections. The `master.properties` file contains configuration that is common to all instances, while `config.properties` is used for settings specific to each instance.

#### `master.properties`
This file holds the primary configuration for the application, including endpoints, credentials, and debugging flags.

-   **`db.url`**: The JDBC URL for the SQLite database. (e.g., `jdbc:sqlite:Apollo.db`)
-   **`mqtt.url`**: The URL of the MQTT broker. (e.g., `tcp://localhost:1883`)
-   **`server.url`**: The WebSocket server URL for receiving patch data. (e.g., `wss://lifesignals.co.in`)
-   **`auth.url`**: The authentication service URL to get access tokens.
-   **`cred.api`**: The API endpoint for credentials.
-   **`details.api`**: The API endpoint for fetching pgroup names.
-   **`fallback.user`**: The fallback username for authentication.
-   **`fallback.pass`**: The fallback password for authentication.
-   **`source`**: A source identifier for this data relay instance.

##### Debugging Flags in `master.properties`
-   **`log.to.console`**: Set to `true` to enable verbose, real-time logging to the console.
-   **`use.test.url`**: A boolean flag to determine whether to use a test URL for services.
-   **`write.to.file`**: Set to `true` to save the final processed JSON payloads to local files.

#### `config.properties`
This file contains settings that are specific to a particular instance of the application.

-   **`jetty.server.port`**: The port for the internal Jetty server. (e.g., `8093`)
-   **`mqtt.client.id`**: A unique client ID for this instance of the data-receiver. (e.g., `Apollo_MQTT_Client`)

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