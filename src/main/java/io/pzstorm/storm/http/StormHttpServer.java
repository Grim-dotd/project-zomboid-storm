package io.pzstorm.storm.http;

import static io.pzstorm.storm.logging.StormLogger.LOGGER;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;

/**
 * Owns the single shared {@link HttpServer} instance for Storm and its mods. Opt-in via {@code
 * -Dstorm.http.port=<port>}; if the property is absent or not a positive integer, the server never
 * starts. It binds to {@code -Dstorm.http.host}, which defaults to loopback because {@code /eval}
 * and {@code /reload} run arbitrary code with no auth. All requests are delegated to {@link
 * HttpEndpointDispatcher}.
 */
public class StormHttpServer {

    public static final String PORT_PROPERTY = "storm.http.port";
    public static final String HOST_PROPERTY = "storm.http.host";
    public static final String DEFAULT_HOST = "127.0.0.1";

    private static volatile HttpServer server;
    private static volatile int boundPort = -1;

    private StormHttpServer() {}

    /**
     * Start the server on the given port if it isn't already running. A single root context {@code
     * /} routes every request through {@link HttpEndpointDispatcher}.
     */
    public static synchronized void start(int port) {
        if (server != null) {
            LOGGER.debug("Storm HTTP server already running on port {}", boundPort);
            return;
        }
        String host = configuredHost();
        try {
            HttpServer created = HttpServer.create(new InetSocketAddress(host, port), 0);
            created.createContext("/", HttpEndpointDispatcher::dispatch);
            startWithDaemonDispatcher(created);
            server = created;
            boundPort = created.getAddress().getPort();
            LOGGER.info("Storm HTTP server started on {}:{}", host, boundPort);
        } catch (IOException e) {
            LOGGER.error("Failed to start Storm HTTP server on {}:{}", host, port, e);
        }
    }

    /**
     * The JDK's HTTP-Dispatcher thread inherits daemon status from the thread that calls {@link
     * HttpServer#start()} and must end up a daemon here: PZ's client quit path returns from the
     * game thread without {@code System.exit} (GameWindow.exit → onGameThreadExited), so a
     * non-daemon dispatcher keeps the dead game's JVM alive indefinitely — invisibly holding
     * workshop-item file locks that fail every Steam update of the Storm item — and the shutdown
     * hook that stops this server only runs once the JVM is already exiting.
     */
    private static void startWithDaemonDispatcher(HttpServer created) {
        Thread starter = new Thread(created::start, "storm-http-start");
        starter.setDaemon(true);
        starter.start();
        try {
            starter.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public static synchronized void stop() {
        HttpServer s = server;
        if (s == null) {
            return;
        }
        LOGGER.info("Stopping Storm HTTP server on port {}", boundPort);
        s.stop(1);
        server = null;
        boundPort = -1;
    }

    public static boolean isRunning() {
        return server != null;
    }

    public static int getPort() {
        return boundPort;
    }

    /** Read {@value #HOST_PROPERTY}, or {@value #DEFAULT_HOST} if unset. */
    public static String configuredHost() {
        String raw = System.getProperty(HOST_PROPERTY);
        return raw == null || raw.isBlank() ? DEFAULT_HOST : raw.trim();
    }

    /** Read {@value #PORT_PROPERTY} and return the port, or {@code -1} if unset/invalid. */
    public static int configuredPort() {
        String raw = System.getProperty(PORT_PROPERTY);
        if (raw == null || raw.isBlank()) {
            return -1;
        }
        try {
            int port = Integer.parseInt(raw.trim());
            return port > 0 ? port : -1;
        } catch (NumberFormatException e) {
            LOGGER.warn("Invalid {} value '{}', HTTP server will not start", PORT_PROPERTY, raw);
            return -1;
        }
    }
}
