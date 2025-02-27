package codes.dreaming.dreamingQueue;

import com.google.inject.Inject;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
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

import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

@Plugin(id = DreamingQueue.PLUGIN_ID, name = "DreamingQueue", version = BuildConstants.VERSION, url = "https://dreaming.codes", authors = {"DreamingCodes"})
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

        DreamingQueue.INSTANCE = new DreamingQueueEventHandler(logger, configHelper, proxyServer, this, targetServer, queueServer);

        proxyServer.getEventManager().register(this, DreamingQueue.INSTANCE);

        CommandManager commandManager = proxyServer.getCommandManager();

        CommandMeta reloadCommandMeta = commandManager.metaBuilder("queueReload").plugin(this).build();
        BrigadierCommand reloadCommand = new BrigadierCommand(BrigadierCommand.literalArgumentBuilder("queueReload").requires(source -> source.hasPermission(PLUGIN_ID + ".reload")).executes(context -> {
            CommandSource source = context.getSource();

            if (source instanceof Player) {
                source.sendMessage(Component.text("Config reloaded"));
            }

            this.configHelper.loadConfiguration();
            this.logger.info("Config reloaded");

            return Command.SINGLE_SUCCESS;
        }).build());

        CommandMeta manageQueueCommandMeta = commandManager.metaBuilder("manageQueue").plugin(this).build();
        BrigadierCommand manageQueueCommand = new BrigadierCommand(BrigadierCommand.literalArgumentBuilder("manageQueue")
                .requires(source -> source.hasPermission(PLUGIN_ID + ".manageQueue"))
                .then(BrigadierCommand.literalArgumentBuilder("removeFromGrace")
                        .then(BrigadierCommand.requiredArgumentBuilder("player", StringArgumentType.word())
                                .suggests((context, builder) -> {
                                    DreamingQueue.INSTANCE.getPlayersWithGrace().forEach(builder::suggest);
                                    builder.suggest("all");
                                    return builder.buildFuture();
                                })
                                .executes(context -> {
                                    CommandSource source = context.getSource();

                                    String argumentProvider = context.getArgument("player", String.class);

                                    if (argumentProvider.equals("all")) {
                                        DreamingQueue.INSTANCE.removePlayerFromGrace();

                                        source.sendMessage(Component.text("Removed all players from grace"));
                                        return Command.SINGLE_SUCCESS;
                                    }

                                    UUID player = DreamingQueue.INSTANCE.playerNamesCache.getIfPresent(argumentProvider);

                                    if (player != null) {
                                        DreamingQueue.INSTANCE.removePlayerFromGrace(player);
                                        if (this.proxyServer.getPlayer(player).isPresent()) {
                                            try {
                                                DreamingQueue.INSTANCE.resetPlayerQueue(player);
                                            } catch (SerializationException e) {
                                                // Ignore errors
                                            }
                                        }
                                        source.sendMessage(Component.text("Removed player from grace"));
                                    } else {
                                        source.sendMessage(Component.text("No such player"));
                                    }

                                    return Command.SINGLE_SUCCESS;
                                })
                        )));


        // Command to check a player's online time
        CommandMeta onlineTimeCommandMeta = commandManager.metaBuilder("onlinetime").plugin(this).build();
        BrigadierCommand onlineTimeCommand = new BrigadierCommand(BrigadierCommand.literalArgumentBuilder("onlinetime")
                .requires(source -> source.hasPermission(PLUGIN_ID + ".onlinetime"))
                .then(BrigadierCommand.requiredArgumentBuilder("player", StringArgumentType.word())
                        .suggests((context, builder) -> {
                            proxyServer.getAllPlayers().forEach(player -> builder.suggest(player.getUsername()));
                            return builder.buildFuture();
                        })
                        .executes(context -> {
                            CommandSource source = context.getSource();
                            String argumentProvider = context.getArgument("player", String.class);
                            
                            // Try to get the player's UUID from the cache
                            final UUID playerUuidFromCache = INSTANCE.playerNamesCache.getIfPresent(argumentProvider);
                            
                            // Use an array to hold a mutable reference that can be modified in the lambda
                            final UUID[] playerUuidHolder = new UUID[1];
                            playerUuidHolder[0] = playerUuidFromCache;
                            
                            // If UUID not in cache, try to get from online player
                            if (playerUuidHolder[0] == null) {
                                proxyServer.getPlayer(argumentProvider).ifPresent(player -> {
                                    playerUuidHolder[0] = player.getUniqueId();
                                    INSTANCE.playerNamesCache.put(argumentProvider, player.getUniqueId());
                                });
                            }
                            
                            if (playerUuidHolder[0] != null) {
                                // Get the player's UUID from the holder
                                final UUID playerUUID = playerUuidHolder[0];
                                
                                // Get the stored online time
                                final long[] totalOnlineTime = {INSTANCE.getPlayerOnlineTime(playerUUID)};
                                
                                // If the player is currently online, add their current session time
                                proxyServer.getPlayer(playerUUID).ifPresent(player -> {
                                    Instant loginTime = INSTANCE.getCurrentSessionStart(playerUUID);
                                    if (loginTime != null) {
                                        long sessionSeconds = Duration.between(loginTime, Instant.now()).getSeconds();
                                        totalOnlineTime[0] += sessionSeconds;
                                    }
                                });
                                
                                // Format the time nicely
                                long hours = totalOnlineTime[0] / 3600;
                                long minutes = (totalOnlineTime[0] % 3600) / 60;
                                long seconds = totalOnlineTime[0] % 60;
                                
                                source.sendMessage(Component.text(String.format(
                                        "%s has been online for %d hours, %d minutes, and %d seconds",
                                        argumentProvider, hours, minutes, seconds)));
                            } else {
                                source.sendMessage(Component.text("Player not found or has no online time recorded"));
                            }
                            
                            return Command.SINGLE_SUCCESS;
                        })
                ));

        commandManager.register(reloadCommandMeta, reloadCommand);
        commandManager.register(manageQueueCommandMeta, manageQueueCommand);
        commandManager.register(onlineTimeCommandMeta, onlineTimeCommand);
    }
}
