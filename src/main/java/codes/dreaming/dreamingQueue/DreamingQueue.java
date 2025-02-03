package codes.dreaming.dreamingQueue;

import com.google.inject.Inject;
import com.mojang.brigadier.Command;
import com.velocitypowered.api.command.BrigadierCommand;
import com.velocitypowered.api.command.CommandManager;
import com.velocitypowered.api.command.CommandMeta;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import net.kyori.adventure.text.Component;

import org.spongepowered.configurate.serialize.SerializationException;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

@Plugin(id = DreamingQueue.PLUGIN_ID, name = "DreamingQueue", version = BuildConstants.VERSION, url = "https://dreaming.codes", authors = {
    "DreamingCodes" })
public class DreamingQueue {
  public static final String PLUGIN_ID = "dreamingqueue";

  private final Logger logger;
  private final ProxyServer proxyServer;

  private final ConfigHelper configHelper;

  private static DreamingQueueEventHandler INSTANCE;

  static final Set<UUID> skipPlayers = ConcurrentHashMap.newKeySet();

  public static boolean skipPlayer(UUID uuid) {
    return skipPlayers.add(uuid);
  }

  /**
   * Requeue already joined player
   */
  public static void requeuePlayer(Player player) throws SerializationException {
    DreamingQueue.INSTANCE.handleAlreadyInPlayerRequeue(player);
  }

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

    DreamingQueue.INSTANCE = new DreamingQueueEventHandler(logger, configHelper, proxyServer, this, targetServer,
        queueServer);

    proxyServer.getEventManager().register(this, DreamingQueue.INSTANCE);

    CommandManager commandManager = proxyServer.getCommandManager();

    CommandMeta commandMeta = commandManager.metaBuilder("queueReload").plugin(this).build();
    BrigadierCommand reloadCommand = new BrigadierCommand(BrigadierCommand.literalArgumentBuilder("queueReload")
        .requires(source -> source.hasPermission(PLUGIN_ID + ".reload"))
        .executes(context -> {
          CommandSource source = context.getSource();

          if (source instanceof Player) {
            source.sendMessage(Component.text("Config reloaded"));
          }

          this.configHelper.loadConfiguration();
          this.logger.info("Config reloaded");

          return Command.SINGLE_SUCCESS;
        }).build());

    commandManager.register(commandMeta, reloadCommand);
  }
}
