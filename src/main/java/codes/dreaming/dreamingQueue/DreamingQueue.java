package codes.dreaming.dreamingQueue;

import com.google.inject.Inject;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import org.spongepowered.configurate.serialize.SerializationException;

import java.util.logging.Logger;

@Plugin(id = DreamingQueue.PLUGIN_ID, name = "DreamingQueue", version = BuildConstants.VERSION, url = "https://dreaming.codes", authors = {"DreamingCodes"})
public class DreamingQueue {
    public static final String PLUGIN_ID = "dreamingqueue";

    private Logger logger;
    private ProxyServer proxyServer;

    private final ConfigHelper configHelper;

    @Inject
    public DreamingQueue(ProxyServer server, Logger logger) {
        this.logger = logger;
        this.proxyServer = server;

        this.configHelper = new ConfigHelper(logger);
    }

    @Subscribe
    public void onProxyInitialization(ProxyInitializeEvent event) throws SerializationException {
        this.configHelper.loadConfiguration();

        RegisteredServer targetServer = proxyServer.getServer(configHelper.getTargetServer()).orElseThrow();
        RegisteredServer queueServer = proxyServer.getServer(configHelper.getQueueServer()).orElseThrow();

        proxyServer.getEventManager().register(this, new DreamingQueueEventHandler(logger, configHelper, targetServer, queueServer));
    }
}
