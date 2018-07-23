package fr.zelus.jarvis.server;

import fr.inria.atlanmod.commons.log.Log;
import fr.zelus.jarvis.core.JarvisException;
import fr.zelus.jarvis.io.WebhookEventProvider;
import org.apache.commons.configuration2.Configuration;
import org.apache.http.config.SocketConfig;
import org.apache.http.impl.bootstrap.HttpServer;
import org.apache.http.impl.bootstrap.ServerBootstrap;

import java.io.IOException;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static fr.inria.atlanmod.commons.Preconditions.checkNotNull;

/**
 * The REST server used to receive external webhooks.
 * <p>
 * The {@link JarvisServer} provides a simple REST API that accepts POST methods on port {@code 5000}. Incoming
 * requests are parsed and sent to the registered {@link WebhookEventProvider}s, that transform the
 * original request into {@link fr.zelus.jarvis.intent.EventInstance}s that can be used to trigger actions.
 *
 * @see #registerWebhookEventProvider(WebhookEventProvider)
 */
public class JarvisServer {

    /**
     * The {@link Configuration} key to store the server port to use.
     */
    public static String SERVER_PORT_KEY = "jarvis.server.port";

    /**
     * The default port to use.
     * <p>
     * The server port can be customized in the constructor's {@link Configuration} using the {@link #SERVER_PORT_KEY}
     * key.
     */
    private static int DEFAULT_SERVER_PORT = 5000;

    /**
     * The port used to receive input requests.
     */
    private int portNumber;

    /**
     * The {@link HttpServer} used to receive input requests.
     */
    private HttpServer server;

    /**
     * The {@link WebhookEventProvider}s to notify when a request is received.
     * <p>
     * These {@link WebhookEventProvider}s are used to parse the input requests and create the corresponding
     * {@link fr.zelus.jarvis.intent.EventInstance}s that can be used to trigger actions.
     */
    private Set<WebhookEventProvider> webhookEventProviders;

    /**
     * Constructs a new {@link JarvisServer} with the given {@link Configuration}.
     * <p>
     * The provided {@link Configuration} is used to specify the port the server should listen to (see
     * {@link #SERVER_PORT_KEY}). If the {@link Configuration} does not specify a port the default value ({@code
     * 5000}) is used.
     * <p>
     * <b>Note:</b> this method does not start the underlying {@link HttpServer}. Use {@link #start()} to start the
     * {@link HttpServer} in a dedicated thread.
     *
     * @param configuration the {@link Configuration} used to initialize the {@link JarvisServer}
     * @see #start()
     * @see #stop()
     */
    public JarvisServer(Configuration configuration) {
        checkNotNull(configuration, "Cannot start the %s with the provided %s: %s", this.getClass().getSimpleName
                (), Configuration.class.getSimpleName(), configuration);
        Log.info("Starting %s", this.getClass().getSimpleName());
        if(configuration.containsKey(SERVER_PORT_KEY)) {
            this.portNumber = configuration.getInt(SERVER_PORT_KEY);
            Log.info("Sever listening to port {0}", this.portNumber);
        } else  {
            Log.info("No port to listen to specified, using the default port {0}", DEFAULT_SERVER_PORT);
            this.portNumber = DEFAULT_SERVER_PORT;
        }
        webhookEventProviders = new HashSet<>();
        SocketConfig socketConfig = SocketConfig.custom()
                .setSoTimeout(15000)
                .setTcpNoDelay(true)
                .build();

        server = ServerBootstrap.bootstrap()
                .setListenerPort(portNumber)
                .setServerInfo("Jarvis/1.1")
                .setSocketConfig(socketConfig)
                .registerHandler("*", new HttpHandler(this))
                .create();
    }

    /**
     * Starts the underlying {@link HttpServer}.
     * <p>
     * This method registered a shutdown hook that is used to close the {@link HttpServer} when the application
     * terminates. To manually close the underlying {@link HttpServer} see {@link #stop()}.
     */
    public void start() {
        try {
            this.server.start();
        } catch (IOException e) {
            throw new JarvisException("Cannot start the JarvisServer", e);
        }
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            server.shutdown(5, TimeUnit.SECONDS);
        }));
        Log.info("JarvisServer started, listening on localhost:{0}", portNumber);
    }

    /**
     * Stops the underlying {@link HttpServer}.
     */
    public void stop() {
        Log.info("Stopping JarvisServer");
        server.shutdown(5, TimeUnit.SECONDS);
    }

    /**
     * Register a {@link WebhookEventProvider}.
     * <p>
     * The registered {@code webhookEventProvider} will be notified when a new request is received. If the provider
     * supports the request content type (see {@link WebhookEventProvider#acceptContentType(String)}, it will receive
     * the request content that will be used to create the associated {@link fr.zelus.jarvis.intent.EventInstance}.
     *
     * @param webhookEventProvider the {@link WebhookEventProvider} to register
     * @see #notifyWebhookEventProviders(String, Object)
     * @see WebhookEventProvider#acceptContentType(String)
     * @see WebhookEventProvider#handleContent(Object)
     */
    public void registerWebhookEventProvider(WebhookEventProvider webhookEventProvider) {
        this.webhookEventProviders.add(webhookEventProvider);
    }

    /**
     * Unregistered a {@link WebhookEventProvider}.
     * <p>
     * The provided {@code webhookEventProvider} will not be notified when new request are received, and cannot be
     * used to create {@link fr.zelus.jarvis.intent.EventInstance}s.
     *
     * @param webhookEventProvider the {@link WebhookEventProvider} to unregister
     */
    public void unregisterWebhookEventProvider(WebhookEventProvider webhookEventProvider) {
        this.webhookEventProviders.remove(webhookEventProvider);
    }

    /**
     * Notifies the registered {@link WebhookEventProvider}s that a new request has been handled.
     * <p>
     * This method asks each registered {@link WebhookEventProvider} if it accepts the given {@code contentType}. If
     * so, the provided {@code content} is sent to the {@link WebhookEventProvider} that will create the associated
     * {@link fr.zelus.jarvis.intent.EventInstance}.
     *
     * @param contentType the content type of the received request
     * @param content     the content of the received request
     * @see #registerWebhookEventProvider(WebhookEventProvider)
     * @see WebhookEventProvider#acceptContentType(String)
     * @see WebhookEventProvider#handleContent(Object)
     */
    public void notifyWebhookEventProviders(String contentType, Object content) {
        for (WebhookEventProvider webhookEventProvider : webhookEventProviders) {
            if (webhookEventProvider.acceptContentType(contentType)) {
                webhookEventProvider.handleContent(content);
            }
        }
    }
}
