package com.lsmed.connectors.wsReceiver;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.FileInputStream;
import java.io.IOException;
import java.util.Properties;

public class ConfigurationManager {

    private static final Logger LOG = LogManager.getLogger(ConfigurationManager.class);
    private static ConfigurationManager instance;
    private final Properties properties;
    private static final String MASTER_CONFIG_FILE = "/configFiles/master.properties";
    private static final String DEFAULT_INSTANCE_CONFIG_FILE = "/configFiles/config.properties";

    private ConfigurationManager() {
        properties = new Properties();
        loadConfig();
    }

    public static synchronized ConfigurationManager getInstance() {
        if (instance == null) {
            instance = new ConfigurationManager();
        }
        return instance;
    }

    private void loadConfig() {
        // Load instance-specific configuration first
        String configFilePath = System.getProperty("config.file.path");
        if (configFilePath == null || configFilePath.isEmpty()) {
            configFilePath = DEFAULT_INSTANCE_CONFIG_FILE;
        }

        try (FileInputStream instanceInput = new FileInputStream(configFilePath)) {
            properties.load(instanceInput);
            LOG.warn("Instance configuration loaded from: {}", configFilePath);
        } catch (IOException e) {
            LOG.warn("Instance configuration file not found or could not be loaded: {}", e.getMessage());
            // Continue execution, as master file might be sufficient
        }

        // Load master configuration, which will override any duplicate instance properties
        String masterConfigFilePath = System.getProperty("master.file.path");
        if (masterConfigFilePath == null || masterConfigFilePath.isEmpty()) {
            masterConfigFilePath = MASTER_CONFIG_FILE;
        }

        try (FileInputStream masterInput = new FileInputStream(masterConfigFilePath)) {
            properties.load(masterInput);
            LOG.info("Master configuration loaded from: {}", masterConfigFilePath);
        } catch (IOException e) {
            LOG.error("Error loading master config file: {}", e.getMessage());
            throw new RuntimeException("Failed to load master configuration.", e);
        }

        final String logLevel = Boolean.parseBoolean(getProperty("log.to.console")) ? "DEBUG" : "OFF";
        System.setProperty("logToConsoleLevel", logLevel);
    }

    public String getServerUrl() {
        return properties.getProperty("server.url");
    }

    public String getAuthUrl() {
        return properties.getProperty("auth.url");
    }

    public String getDbUrl() {
        return properties.getProperty("db.url");
    }

    public String getSource() {
        return properties.getProperty("source");
    }

    public String getMqttClientId() {
        return properties.getProperty("mqtt.client.id");
    }

    public String getProperty(String key) {
        return properties.getProperty(key);
    }

    public String getJettyServerPort() {
        return properties.getProperty("jetty.server.port");
    }

    public String getUseTestUrl() {
        return properties.getProperty("use.test.url");
    }

    public String getWriteToFile() {
        return properties.getProperty("write.to.file");
    }

    public String getLogToConsole() {
        return properties.getProperty("log.to.console");
    }
}