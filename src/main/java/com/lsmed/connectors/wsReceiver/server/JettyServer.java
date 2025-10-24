package com.lsmed.connectors.wsReceiver.server;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.servlet.ServletHandler;
import org.eclipse.jetty.util.thread.QueuedThreadPool;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

public class JettyServer {

    private static final Logger LOG = LogManager.getLogger(JettyServer.class.getSimpleName());

    private Server server;

    private int serverPort = 8092; // Default port

    public JettyServer() {
        loadConfig();
    }

    private void loadConfig() {
        Properties properties = new Properties();
        String configFilePath = System.getProperty("config.file.path");
        if (configFilePath == null) {
            configFilePath = "config.properties"; // Default if not specified
        }
        try (InputStream input = new FileInputStream(configFilePath)) {
            properties.load(input);
            String portProperty = properties.getProperty("jetty.server.port");
            if (portProperty != null && !portProperty.isEmpty()) {
                try {
                    serverPort = Integer.parseInt(portProperty);
                } catch (NumberFormatException e) {
                    LOG.warn("Invalid jetty.server.port value in config: " + portProperty + ". Using default port: " + serverPort);
                }
            } else {
                LOG.info("jetty.server.port not found in config. Using default port: " + serverPort);
            }
        } catch (IOException e) {
            LOG.error("Error loading config file. Using default port: " + serverPort, e);
        }
    }

    public void start() throws Exception {

        int maxThreads = 50;
        int minThreads = 5;
        int idleTimeout = 120;

        QueuedThreadPool threadPool = new QueuedThreadPool(maxThreads, minThreads, idleTimeout);

        server = new Server(threadPool);
        ServerConnector connector = new ServerConnector(server);
        connector.setPort(serverPort);
        server.setConnectors(new Connector[] { connector });

        ServletHandler servletHandler = new ServletHandler();
        server.setHandler(servletHandler);

        servletHandler.addServletWithMapping(PatchListServlet.class, "/patchList");

        server.start();
        LOG.info("WebServer started at port: " + serverPort);
    }

    void stop() throws Exception {
        server.stop();
    }
}