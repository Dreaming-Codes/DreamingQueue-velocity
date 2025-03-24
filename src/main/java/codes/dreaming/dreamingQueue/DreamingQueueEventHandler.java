package codes.dreaming.dreamingQueue;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
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
import org.spongepowered.configurate.serialize.SerializationException;

import javax.annotation.Nullable;
import java.text.MessageFormat;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import java.util.stream.Collectors;

class DisconnectedQueuePlayer {
    private final Player player;
    private final int position;
    private final int priority;

    DisconnectedQueuePlayer(Player player, int position, int priority) {
        this.player = player;
        this.position = position;
        this.priority = priority;
    }

    public Player getPlayer() {
        return player;
    }

    public int getPosition() {
        return position;
    }

    public int getPriority() {
        return priority;
    }
}

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
    private final Cache<UUID, DisconnectedQueuePlayer> leftGracePlayers;
    
    /**
     * Cache to hold players who left with their exact position
     * (Guava's Cache is thread‐safe.)
     */
    private final Cache<UUID, DisconnectedQueuePlayer> leftPositionPlayers;

    public final Cache<String, UUID> playerNamesCache;

    public static final String LuckPermsMetaPriorityKey = DreamingQueue.PLUGIN_ID + ":priority";

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

        this.leftGracePlayers = CacheBuilder.newBuilder()
            .expireAfterWrite(configHelper.getGraceMinutes(), TimeUnit.MINUTES)
            .build();
            
        // Add a removal listener to update the boss bars when a ghost position expires
        this.leftPositionPlayers = CacheBuilder.newBuilder()
            .expireAfterWrite(configHelper.getPositionExpirationMinutes(), TimeUnit.MINUTES)
            .removalListener(notification -> {
                try {
                    synchronized (queueLock) {
                        updateBossBarsInternal();
                    }
                } catch (Exception e) {
                    // Ignore exceptions during removal
                }
            })
            .build();
        this.playerNamesCache = CacheBuilder.newBuilder().build();
    }

    @Nullable
    private Integer getLuckpermsGracePriority(UUID uuid) {
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
        try {
            int activeTotal = queuedPlayers.size();
            
            // Count ghost positions from players with saved positions
            int ghostPositions = 0;
            if (configHelper.isRetainExactPosition()) {
                ghostPositions = (int) leftPositionPlayers.size();
            }
            
            int totalWithGhosts = activeTotal + ghostPositions;
            
            for (int i = 0; i < activeTotal; i++) {
                QueuedPlayer qp = queuedPlayers.get(i);
                int position = i + 1;
                BossBar bar = buildBossBar(position, totalWithGhosts);
                qp.paintBossBar(bar);
            }
        } catch (Exception e) {
            logger.warning("Error updating boss bars: " + e.getMessage());
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
        synchronized (queueLock) {
            if (targetServer.getPlayersConnected().size() < configHelper.getMaxPlayers() && queuedPlayers.isEmpty() && getMonitoredServerStatus()) {
                return false;
            }
        }

        if (player.hasPermission(DreamingQueue.PLUGIN_ID + ".bypass_queue")) {
            return false;
        }

        int playerPriority = 0;
        DisconnectedQueuePlayer positionPlayer = null;
        
        try {
            // First check if we should use exact position
            if (configHelper.isRetainExactPosition()) {
                positionPlayer = leftPositionPlayers.getIfPresent(player.getUniqueId());
                if (positionPlayer != null) {
                    leftPositionPlayers.invalidate(player.getUniqueId());
                    leftGracePlayers.invalidate(player.getUniqueId()); // Also clean up grace cache
                    
                    // Since a ghost position is now filled by a real player, update all boss bars
                    
                    logger.info(MessageFormat.format("Player({0}) rejoined with exact position {1}", 
                        player.getUsername(), positionPlayer.getPosition()));
                    
                    QueuedPlayer queuedPlayer = new QueuedPlayer(player, positionPlayer.getPriority());
                    synchronized (queueLock) {
                        // Insert player at specific position, but capped at queuedPlayers.size()
                        int insertPosition = Math.min(positionPlayer.getPosition(), queuedPlayers.size());
                        queuedPlayers.add(insertPosition, queuedPlayer);
                        updateBossBarsInternal();
                    }
                    return true;
                }
            }
            
            // Fall back to grace period priority if exact position not found
            DisconnectedQueuePlayer gracePlayer = leftGracePlayers.getIfPresent(player.getUniqueId());
            if (gracePlayer != null) {
                leftGracePlayers.invalidate(player.getUniqueId());
                playerPriority = configHelper.getGracePriority();
            }
        } catch (Exception e) {
            // If anything fails, continue with normal queue handling
            logger.warning("Error handling player position: " + e.getMessage());
        }

        Integer lpPriority = getLuckpermsGracePriority(player.getUniqueId());
        if (lpPriority != null && lpPriority > playerPriority) {
            playerPriority = lpPriority;
        }

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
        return leftGracePlayers.asMap().values().stream().map(p -> p.getPlayer().getUsername()).collect(Collectors.toSet());
    }

    public final void removePlayerFromGrace(UUID player) {
        leftGracePlayers.invalidate(player);
        leftPositionPlayers.invalidate(player);
    }

    public final void removePlayerFromGrace() {
        leftGracePlayers.invalidateAll();
        leftPositionPlayers.invalidateAll();
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

        if (event.getInitialServer().isEmpty() || event.getInitialServer().get() != queueServer) {
            return;
        }

        if (DreamingQueue.skipPlayers.remove(event.getPlayer().getUniqueId())) {
            return;
        }

        if (!handlePlayerEnter(event.getPlayer())) {
            event.setInitialServer(targetServer);
        }
    }

    @Subscribe
    private void onPlayerMove(ServerConnectedEvent event) throws SerializationException {
        if (event.getPreviousServer().isPresent() && event.getPreviousServer().get() == targetServer) {
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

    @Subscribe
    private void onPlayerDisconnect(DisconnectEvent event) throws SerializationException {
        int playerQueuePosition = -1;
        int playerPriority = 0;
        
        // Check if player was in queue and save their position
        synchronized (queueLock) {
            for (int i = 0; i < queuedPlayers.size(); i++) {
                QueuedPlayer qp = queuedPlayers.get(i);
                if (qp.player().equals(event.getPlayer())) {
                    playerQueuePosition = i;
                    playerPriority = qp.priority();
                    qp.hideBar();
                    queuedPlayers.remove(i);
                    updateBossBarsInternal();
                    break;
                }
            }
        }
        
        // If player was in queue, store their position for rejoining
        if (playerQueuePosition >= 0 && configHelper.isRetainExactPosition()) {
            logger.info(MessageFormat.format("Player({0}) disconnected from position {1}, saving position for {2} minutes", 
                event.getPlayer().getUsername(), playerQueuePosition, configHelper.getPositionExpirationMinutes()));
            
            DisconnectedQueuePlayer positionPlayer = new DisconnectedQueuePlayer(
                event.getPlayer(), 
                playerQueuePosition, 
                playerPriority
            );
            leftPositionPlayers.put(event.getPlayer().getUniqueId(), positionPlayer);
            
            // Update boss bars to show the ghost position
            synchronized (queueLock) {
                updateBossBarsInternal();
            }
        }

        Optional<ServerConnection> disconnectedFrom = event.getPlayer().getCurrentServer();

        if (disconnectedFrom.isEmpty()) {
            logger.warning("Unable to get which server the player was connected to, ignoring");
            return;
        }
        
        // Only handle for non-queue server disconnections
        if (!disconnectedFrom.get().getServerInfo().equals(queueServer.getServerInfo())) {
            // Store in grace period cache regardless of queue position
            DisconnectedQueuePlayer gracePlayer = new DisconnectedQueuePlayer(
                event.getPlayer(),
                playerQueuePosition, 
                configHelper.getGracePriority()
            );
            leftGracePlayers.put(event.getPlayer().getUniqueId(), gracePlayer);
            
            if (getMonitoredServerStatus()) {
                movePlayerFromQueue();
            }
        }
    }
}
