package codes.dreaming.dreamingQueue;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.PostLoginEvent;
import com.velocitypowered.api.event.player.KickedFromServerEvent;
import com.velocitypowered.api.event.player.PlayerChooseInitialServerEvent;
import com.velocitypowered.api.event.player.ServerConnectedEvent;
import com.velocitypowered.api.event.player.ServerPreConnectEvent;
import com.velocitypowered.api.proxy.ConnectionRequestBuilder;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.scheduler.ScheduledTask;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.model.user.User;
import net.luckperms.api.model.user.UserManager;
import net.luckperms.api.node.NodeType;
import net.luckperms.api.node.types.MetaNode;
import org.spongepowered.configurate.serialize.SerializationException;

import javax.annotation.Nullable;
import java.text.MessageFormat;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import java.util.stream.Collectors;

final class QueuedPlayer implements Comparable<QueuedPlayer> {
    private final Player player;
    private final int priority;
    @Nullable
    private BossBar queueBar;

    QueuedPlayer(Player player, int priority) {
        this.player = player;
        this.priority = priority;
    }

    public void paintBossBar() {
        if (this.queueBar == null) {
            return;
        }
        this.player.showBossBar(queueBar);
    }

    public void paintBossBar(BossBar bossBar) {
        if (this.queueBar != null) {
            this.player.hideBossBar(this.queueBar);
        }
        this.player.showBossBar(bossBar);
        this.queueBar = bossBar;
    }

    public void hideBar() {
        if (this.queueBar != null) {
            this.player.hideBossBar(this.queueBar);
        }
    }

    public Player player() {
        return player;
    }

    public int priority() {
        return priority;
    }

    @Override
    public int compareTo(QueuedPlayer otherPlayer) {
        return Integer.compare(this.priority, otherPlayer.priority);
    }

    @Override
    public boolean equals(Object obj) {
        if (obj == this) return true;
        if (obj == null || obj.getClass() != this.getClass()) return false;
        QueuedPlayer that = (QueuedPlayer) obj;
        return this.player.equals(that.player);
    }

    @Override
    public int hashCode() {
        return player.hashCode();
    }
}

public class DreamingQueueEventHandler {
    private final Logger logger;
    private final ConfigHelper configHelper;
    private final DreamingQueue pluginInstance;
    private final ProxyServer proxyServer;
    private final RegisteredServer targetServer;
    private final RegisteredServer queueServer;

    /*
     * Use dedicated lock objects to synchronize all modifications and iterations on
     * the queuedPlayers list as well as control the scheduling of a periodic task.
     */
    private final Object queueLock = new Object();
    private final Object monitorLock = new Object();

    // List of players in the queue (never exposed directly).
    private final List<QueuedPlayer> queuedPlayers = new ArrayList<>();

    /**
     * Cache to hold players who left during a grace
     * period. (Guava’s Cache is thread‐safe.)
     */
    private final Cache<UUID, Player> leftGracePlayers;

    public final Cache<String, UUID> playerNamesCache;

    public static final String LuckPermsMetaPriorityKey = DreamingQueue.PLUGIN_ID + ":priority";
    public static final String LuckPermsMetaOnlineTimeKey = DreamingQueue.PLUGIN_ID + ":onlinetime";
    
    // Maps player UUIDs to their login time in target server
    private final Map<UUID, Instant> targetServerLoginTimes = new ConcurrentHashMap<>();
    
    // Formula evaluator for priority calculations
    private final FormulaEvaluator formulaEvaluator;

    // A flag and monitor for a “target server available” check.
    private volatile boolean targetOn = false;
    private ScheduledTask targetServerMonitor = null;

    public DreamingQueueEventHandler(Logger logger, ConfigHelper configHelper, ProxyServer proxyServer, DreamingQueue pluginInstance, RegisteredServer targetServer, RegisteredServer queueServer) throws SerializationException {
        this.logger = logger;
        this.configHelper = configHelper;
        this.proxyServer = proxyServer;
        this.pluginInstance = pluginInstance;
        this.targetServer = targetServer;
        this.queueServer = queueServer;
        this.formulaEvaluator = new FormulaEvaluator(logger);

        this.leftGracePlayers = CacheBuilder.newBuilder().expireAfterWrite(configHelper.getGraceMinutes(), TimeUnit.MINUTES).build();
        this.playerNamesCache = CacheBuilder.newBuilder().build();
    }

    /**
     * Gets the raw LuckPerms priority value for a player.
     * @param uuid The player's UUID
     * @return The priority value, or null if not found
     */
    @Nullable
    private Integer getLuckpermsRawPriority(UUID uuid) {
        LuckPerms lpProvider = LuckPermsProvider.get();
        UserManager lpUserManager = lpProvider.getUserManager();
        User lpUser = lpUserManager.getUser(uuid);
        if (lpUser != null) {
            String priority = lpUser.getCachedData().getMetaData().getMetaValue(LuckPermsMetaPriorityKey);

            if (priority != null) {
                try {
                    return Integer.parseInt(priority);
                } catch (NumberFormatException e) {
                    logger.warning("Invalid LuckPerms priority for player " + uuid);
                }
            }
        }
        return null;
    }
    
    /**
     * Calculates player priority using the configured formula.
     * @param uuid The player's UUID
     * @return The calculated priority value
     * @throws SerializationException if there's an error accessing the config
     */
    private int calculatePlayerPriority(UUID uuid) throws SerializationException {
        // Get the formula from config
        String formula = configHelper.getPriorityFormula();
        
        // Get the LuckPerms priority (default to 0 if not set)
        Integer lpPriority = getLuckpermsRawPriority(uuid);
        int priorityValue = (lpPriority != null) ? lpPriority : 0;
        
        // Get the player's online time
        long onlineTime = getPlayerOnlineTime(uuid);
        
        // Create variables map for formula evaluation
        Map<String, Object> variables = new HashMap<>();
        variables.put("lp_priority", priorityValue);
        variables.put("online_time", onlineTime);
        
        // Add grace_active variable (1 if in grace period, 0 otherwise)
        int isInGrace = (leftGracePlayers.getIfPresent(uuid) != null) ? 1 : 0;
        variables.put("grace_active", isInGrace);
        
        // Calculate priority using the formula
        return formulaEvaluator.evaluate(formula, variables, priorityValue);
    }
    
    
    /**
     * Updates the player's online time in LuckPerms metadata.
     * @param uuid The player's UUID
     * @param onlineTimeSeconds The player's total online time in seconds
     */
    private void updatePlayerOnlineTime(UUID uuid, long onlineTimeSeconds) {
        try {
            LuckPerms lpProvider = LuckPermsProvider.get();
            UserManager lpUserManager = lpProvider.getUserManager();
            User lpUser = lpUserManager.getUser(uuid);
            if (lpUser == null) {
                lpUser = lpUserManager.loadUser(uuid).join();
            }
            
            // Remove existing meta node if it exists
            lpUser.data().clear(node -> 
                node.getType() == NodeType.META && 
                ((MetaNode) node).getMetaKey().equals(LuckPermsMetaOnlineTimeKey)
            );
            
            // Add the new meta node
            MetaNode metaNode = MetaNode.builder()
                .key(LuckPermsMetaOnlineTimeKey)
                .value(String.valueOf(onlineTimeSeconds))
                .build();
            
            lpUser.data().add(metaNode);
            
            // Save the changes
            lpUserManager.saveUser(lpUser);
            
            logger.fine("Updated online time for " + uuid + " to " + onlineTimeSeconds + " seconds");
        } catch (Exception e) {
            logger.warning("Failed to update online time for player " + uuid + ": " + e.getMessage());
        }
    }

    /**
     * Build a new BossBar for a given position in queue.
     *
     * @param position The (1-indexed) position in queue.
     * @param total    Total number of queued players.
     * @return a new BossBar instance
     */
    private BossBar buildBossBar(int position, int total) {
        float progress = total == 1 ? 1f : 1 - ((float) (position - 1)) / (total - 1);
        return BossBar.bossBar(Component.text(MessageFormat.format("Sei in coda {0}/{1}", position, total)), progress, BossBar.Color.PURPLE, BossBar.Overlay.PROGRESS);
    }

    /**
     * Update the boss bars for all queued players. This must be called while
     * holding the queueLock.
     */
    private void updateBossBarsInternal() {
        int total = queuedPlayers.size();
        for (int i = 0; i < total; i++) {
            QueuedPlayer qp = queuedPlayers.get(i);
            int position = i + 1;
            BossBar bar = buildBossBar(position, total);
            qp.paintBossBar(bar);
        }
    }

    /**
     * Insert the player in order. We want to place a new player after any
     * player with an equal OR higher priority.
     * Must be called while holding queueLock.
     */
    private void addToQueueInternal(QueuedPlayer newPlayer) {
        int index = 0;
        for (int i = queuedPlayers.size() - 1; i >= 0; i--) {
            if (queuedPlayers.get(i).priority() >= newPlayer.priority()) {
                index = i + 1;
                break;
            }
        }
        queuedPlayers.add(index, newPlayer);
    }

    /**
     * Check if the target server is online by pinging it.
     *
     * <p>If the target server comes online (targetOn becomes true), it will try to
     * move queued players.
     *
     * @return true if online, false otherwise.
     */
    private boolean getTargetServerStatus() {
        boolean serverStatus = false;
        try {
            var pingResult = targetServer.ping().join().getDescriptionComponent();
            serverStatus = pingResult.equals(Component.text("true"));
        } catch (Exception e) {
            // Ignore and assume server not available.
        }

        if (serverStatus && !targetOn) {
            try {
                movePlayerFromQueue();
            } catch (Exception e) {
                // Ignore any exceptions during moving players.
            }
        }
        targetOn = serverStatus;
        return serverStatus;
    }

    /**
     * Monitors the target server by scheduling a task that periodically pings the server.
     *
     * @return the current value of targetOn
     */
    private boolean getMonitoredServerStatus() {
        boolean currentStatus = getTargetServerStatus();
        synchronized (monitorLock) {
            if (!currentStatus && targetServerMonitor == null) {
                targetServerMonitor = proxyServer.getScheduler().buildTask(pluginInstance, task -> {
                    if (getTargetServerStatus()) {
                        task.cancel();
                        synchronized (monitorLock) {
                            targetServerMonitor = null;
                        }
                    }
                }).repeat(5L, TimeUnit.SECONDS).schedule();
            }
        }
        return targetOn;
    }

    /**
     * Attempts to add a player to the queue if the target server is full.
     *
     * @param player the player that is connecting
     * @return true if the player was placed in queue, false if they should
     * bypass the queue.
     * @throws SerializationException if a configuration error occurs.
     */
    private boolean handlePlayerEnter(Player player) throws SerializationException {
        if (targetServer.getPlayersConnected().size() < configHelper.getMaxPlayers() && getMonitoredServerStatus()) {
            return false;
        }

        if (player.hasPermission(DreamingQueue.PLUGIN_ID + ".bypass_queue")) {
            return false;
        }

        // Calculate player priority using the formula
        int playerPriority = calculatePlayerPriority(player.getUniqueId());
        
        logger.info(MessageFormat.format("Player({0}) in queue with priority {1}", player.getUsername(), playerPriority));

        QueuedPlayer queuedPlayer = new QueuedPlayer(player, playerPriority);
        synchronized (queueLock) {
            addToQueueInternal(queuedPlayer);
            updateBossBarsInternal();
        }
        return true;
    }

    /**
     * @param player the player to requeue.
     * @throws SerializationException if a configuration error occurs.
     */
    public void handleAlreadyInPlayerRequeue(Player player) throws SerializationException {
        if (!handlePlayerEnter(player)) {
            player.createConnectionRequest(targetServer).connect().thenApply(result -> {
                if (result.getStatus() != ConnectionRequestBuilder.Status.SUCCESS) {
                    try {
                        logger.info("A player failed to connect to main server after queue " + "asked to bypass it, playerName: " + player.getUsername());
                        player.disconnect(Component.text("Unable to connect to server"));
                    } catch (Exception e) {
                        // Ignore disconnection errors.
                    }
                }
                return null;
            });
        }
    }

    public final Set<String> getPlayersWithGrace() {
        return leftGracePlayers.asMap().values().stream().map(Player::getUsername).collect(Collectors.toSet());
    }

    public final void removePlayerFromGrace(UUID player) {
        leftGracePlayers.invalidate(player);
    }

    public final void removePlayerFromGrace() {
        leftGracePlayers.invalidateAll();
    }
    
    /**
     * Gets the start time of a player's current session in the target server.
     * @param uuid The player's UUID
     * @return The time when the player started their current session in the target server, or null if not in the target server
     */
    public Instant getCurrentSessionStart(UUID uuid) {
        return targetServerLoginTimes.get(uuid);
    }
    
    /**
     * Gets the player's online time from LuckPerms metadata.
     * This method is made public so it can be accessed from DreamingQueue.
     * @param uuid The player's UUID
     * @return The player's online time in seconds, or 0 if not found
     */
    public long getPlayerOnlineTime(UUID uuid) {
        LuckPerms lpProvider = LuckPermsProvider.get();
        UserManager lpUserManager = lpProvider.getUserManager();
        User lpUser = lpUserManager.getUser(uuid);
        if (lpUser != null) {
            String onlineTime = lpUser.getCachedData().getMetaData().getMetaValue(LuckPermsMetaOnlineTimeKey);
            if (onlineTime != null) {
                try {
                    return Long.parseLong(onlineTime);
                } catch (NumberFormatException e) {
                    logger.warning("Invalid LuckPerms online time for player " + uuid);
                }
            }
        }
        return 0;
    }

    private Player removePlayerFromQueue(UUID player) {
        synchronized (queueLock) {
            QueuedPlayer qp = queuedPlayers.stream().filter(queuedPlayer -> queuedPlayer.player().getUniqueId().equals(player)).findAny().orElse(null);
            if (qp == null) {
                return null;
            }
            queuedPlayers.remove(qp);
            qp.hideBar();
            return qp.player();
        }
    }

    public void resetPlayerQueue(UUID player) throws SerializationException {
        Player removedPlayer = removePlayerFromQueue(player);
        handleAlreadyInPlayerRequeue(removedPlayer);
    }

    /**
     * Moves players from the queue to the target server. It moves up to the available
     * number of slots (based on configuration and current connections).
     *
     * @throws SerializationException if a configuration error occurs.
     */
    private void movePlayerFromQueue() throws SerializationException {
        // First, collect all players to be moved in a thread‐safe manner.
        List<QueuedPlayer> toMove = new ArrayList<>();
        synchronized (queueLock) {
            int availableSlots = configHelper.getMaxPlayers() - targetServer.getPlayersConnected().size();
            while (availableSlots > 0 && !queuedPlayers.isEmpty()) {
                QueuedPlayer qp = queuedPlayers.remove(0);
                qp.hideBar();
                toMove.add(qp);
                availableSlots--;
            }
            updateBossBarsInternal();
        }
        // Now connect them outside the lock.
        for (QueuedPlayer qp : toMove) {
            qp.player().createConnectionRequest(targetServer).connect().thenApply(result -> {
                if (result.getStatus() != ConnectionRequestBuilder.Status.SUCCESS) {
                    try {
                        qp.player().disconnect(Component.text("Unable to connect to server"));
                    } catch (Exception e) {
                        // Ignore disconnection errors.
                    }
                }
                return null;
            });
        }
    }

    @Subscribe
    private void onPlayerMoveToMain(ServerPreConnectEvent event) throws SerializationException {
        if (event.getResult().getServer().orElse(null) == targetServer) {
            synchronized (queueLock) {
                Iterator<QueuedPlayer> it = queuedPlayers.iterator();
                while (it.hasNext()) {
                    QueuedPlayer qp = it.next();
                    if (qp.player().equals(event.getPlayer())) {
                        it.remove();
                        qp.hideBar();
                        break;
                    }
                }
                updateBossBarsInternal();
            }
        }
    }

    @Subscribe
    private void onPlayerKicked(KickedFromServerEvent event) {
        this.logger.info(event.getServerKickReason().orElse(Component.text("")).toString());
    }

    @Subscribe
    private void onPlayerEnter(PlayerChooseInitialServerEvent event) throws SerializationException {
        // Add player name to cache
        this.playerNamesCache.put(event.getPlayer().getUsername(), event.getPlayer().getUniqueId());
        
        if (DreamingQueue.skipPlayers.remove(event.getPlayer().getUniqueId())) {
            return;
        }
        if (handlePlayerEnter(event.getPlayer())) {
            event.setInitialServer(queueServer);
        }
    }

    @Subscribe
    private void onPlayerMove(ServerConnectedEvent event) throws SerializationException {
        Player player = event.getPlayer();
        UUID playerUUID = player.getUniqueId();
        
        // If player connected to the target server, start tracking time
        if (event.getServer().getServerInfo().equals(targetServer.getServerInfo())) {
            targetServerLoginTimes.put(playerUUID, Instant.now());
            logger.fine("Player " + player.getUsername() + " connected to target server, starting time tracking");
        }
        
        // If player left the target server
        if (event.getPreviousServer().isPresent() && event.getPreviousServer().get() == targetServer) {
            // Update online time if they were in the target server
            updateOnlineTimeOnLeave(playerUUID);
            
            // Handle queue and server availability
            if (getMonitoredServerStatus()) {
                movePlayerFromQueue();
            }
            if (event.getServer().getServerInfo().equals(queueServer.getServerInfo())) {
                proxyServer.getScheduler().buildTask(pluginInstance, () -> {
                    try {
                        handleAlreadyInPlayerRequeue(event.getPlayer());
                    } catch (Exception e) {
                        // Ignore exceptions.
                    }
                }).delay(10, TimeUnit.SECONDS).schedule();
            }
        }
    }

    /**
     * Updates a player's online time when they leave the target server
     * @param playerUUID The UUID of the player who left
     */
    private void updateOnlineTimeOnLeave(UUID playerUUID) {
        // Get the time when they joined the target server
        Instant loginTime = targetServerLoginTimes.remove(playerUUID);
        if (loginTime != null) {
            // Calculate session time
            long sessionSeconds = Duration.between(loginTime, Instant.now()).getSeconds();
            
            // Get previous total online time and add this session
            long totalOnlineTime = getPlayerOnlineTime(playerUUID) + sessionSeconds;
            
            // Update the player's total online time in LuckPerms
            updatePlayerOnlineTime(playerUUID, totalOnlineTime);
            
            Optional<String> username = proxyServer.getPlayer(playerUUID).map(Player::getUsername);
            logger.info(MessageFormat.format("Player {0} was in target server for {1} seconds, total time: {2} seconds", 
                    username.orElse(playerUUID.toString()), sessionSeconds, totalOnlineTime));
        }
    }

    @Subscribe
    private void onPlayerDisconnect(DisconnectEvent event) throws SerializationException {
        Player player = event.getPlayer();
        UUID playerUUID = player.getUniqueId();
        
        synchronized (queueLock) {
            queuedPlayers.removeIf(qp -> qp.player().equals(player));
            updateBossBarsInternal();
        }

        // If the player was in the target server when they disconnected,
        // update their online time
        Optional<ServerConnection> disconnectedFrom = player.getCurrentServer();
        if (disconnectedFrom.isPresent() && 
            disconnectedFrom.get().getServerInfo().equals(targetServer.getServerInfo())) {
            // Update online time on disconnect from target server
            updateOnlineTimeOnLeave(playerUUID);
        }

        if (disconnectedFrom.isEmpty()) {
            logger.warning("Unable to get which server the player was connected to, ignoring");
            return;
        }
        
        if (!disconnectedFrom.get().getServerInfo().equals(queueServer.getServerInfo())) {
            leftGracePlayers.put(playerUUID, player);
            if (getMonitoredServerStatus()) {
                movePlayerFromQueue();
            }
        }
    }
}
