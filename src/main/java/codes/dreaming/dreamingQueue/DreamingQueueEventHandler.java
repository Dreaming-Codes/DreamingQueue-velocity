package codes.dreaming.dreamingQueue;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.player.PlayerChooseInitialServerEvent;
import com.velocitypowered.api.proxy.ConnectionRequestBuilder;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.model.data.DataType;
import net.luckperms.api.model.user.User;
import net.luckperms.api.model.user.UserManager;
import net.luckperms.api.node.NodeType;
import net.luckperms.api.node.types.MetaNode;
import org.spongepowered.configurate.serialize.SerializationException;

import javax.annotation.Nullable;
import java.text.MessageFormat;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

final class QueuedPlayer {
    private final Player player;
    private final int priority;
    @Nullable
    private BossBar queueBar;

    QueuedPlayer(Player player, int priority) {
        this.player = player;
        this.priority = priority;
    }

    void paintBossBar() {
        if (this.queueBar == null) return;
        this.player.showBossBar(queueBar);
    }

    void paintBossBar(BossBar bossBar) {
        if (this.queueBar != null) {
            this.player.hideBossBar(this.queueBar);
        }
        this.player.showBossBar(bossBar);
        this.queueBar = bossBar;
    }

    void hideBar() {
        if (this.queueBar == null) return;
        this.player.hideBossBar(queueBar);
    }

    public Player player() {
        return player;
    }

    public int priority() {
        return priority;
    }

    @Override
    public boolean equals(Object obj) {
        if (obj == this) return true;
        if (obj == null || obj.getClass() != this.getClass()) return false;
        var that = (QueuedPlayer) obj;
        return Objects.equals(this.player, that.player);
    }

    @Override
    public int hashCode() {
        return Objects.hash(player);
    }
}

public class DreamingQueueEventHandler {
    private final Logger logger;
    private final ConfigHelper configHelper;

    private final RegisteredServer targetServer;
    private final RegisteredServer queueServer;

    private final PriorityQueue<QueuedPlayer> queuedPlayers;

    private final Cache<UUID, Player> leftGracePlayers;

    public static final String LuckPermsMetaPriorityKey = DreamingQueue.PLUGIN_ID + ":priority";

    DreamingQueueEventHandler(Logger logger, ConfigHelper configHelper, RegisteredServer targetServer, RegisteredServer queueServer) throws SerializationException {
        this.logger = logger;
        this.configHelper = configHelper;
        this.targetServer = targetServer;
        this.queueServer = queueServer;

        this.leftGracePlayers = CacheBuilder.newBuilder().expireAfterWrite(configHelper.getGraceMinutes(), TimeUnit.MINUTES).build();
        this.queuedPlayers = new PriorityQueue<>(Comparator.comparingInt(QueuedPlayer::priority).reversed());
    }

    @Nullable
    private Integer getLuckpermsGracePriority(UUID uuid) {
        LuckPerms lpProvider = LuckPermsProvider.get();
        UserManager lpUserManager = lpProvider.getUserManager();
        User lpUser = lpUserManager.getUser(uuid);
        if (lpUser != null) {
            String priority = lpUser.getCachedData().getMetaData().getMetaValue(LuckPermsMetaPriorityKey);

            if (priority == null) return null;

            return Integer.parseInt(priority);
        }
        return null;
    }

    private BossBar buildBossBar(Player player) {
        int position = 1;

        for (QueuedPlayer queuedPlayer : queuedPlayers) {
            if (queuedPlayer.player().equals(player)) break;
            position++;
        }

        float progress = queuedPlayers.size() == 1 ? 1 : 1 - ((float) position - 1) / (queuedPlayers.size() - 1);

        return BossBar.bossBar(Component.text(MessageFormat.format("Sei in coda {0}/{1}", position, queuedPlayers.size() )), progress, BossBar.Color.PURPLE, BossBar.Overlay.PROGRESS);
    }

    private void updateBossBars() {
        for (QueuedPlayer queuedPlayer : queuedPlayers) {
            queuedPlayer.paintBossBar(this.buildBossBar(queuedPlayer.player()));
        }
    }

    /**
     * Handles enter of a player that has been already redirected to a queue server
     * @param player The player to manage
     * @throws SerializationException If there's a config error
     * @return true means the player has been added to the queue, false means it should skip it
     */
    private boolean handlePlayerEnter(Player player) throws SerializationException {
        // If server is not full
        if (targetServer.getPlayersConnected().size() < configHelper.getMaxPlayers()) return false;
        // If it has permission to bypass queue
        if (player.hasPermission(DreamingQueue.PLUGIN_ID + ".bypass_queue")) return false;

        // Apply grace time priority
        int playerPriority = 0;
        if (this.leftGracePlayers.getIfPresent(player.getUniqueId()) != null) {
            playerPriority = this.configHelper.getGracePriority();
        }

        // Apply priority from luckperms meta
        Integer luckpermsPriority = getLuckpermsGracePriority(player.getUniqueId());
        if (luckpermsPriority != null && luckpermsPriority > playerPriority) {
            playerPriority = luckpermsPriority;
        }

        this.logger.info(MessageFormat.format("Player({0}) in queue with priority {1}", player.getUsername(), playerPriority));

        QueuedPlayer queuedPlayer = new QueuedPlayer(player, playerPriority);
        queuedPlayers.add(queuedPlayer);
        this.updateBossBars();

        return true;
    }

    /**
     * Handle a player that's already in a queue server and need to be handled
     */
    public void handleAlreadyInPlayerRequeue(Player player) throws SerializationException {
        if (!this.handlePlayerEnter(player)) {
            player.createConnectionRequest(this.targetServer).connect().thenApply(result -> {
                if (result.getStatus() != ConnectionRequestBuilder.Status.SUCCESS) {
                    try {
                        player.disconnect(Component.text("Unable to connect to server"));
                    } catch (Exception e) {
                        // Ignore disconnection error, player already disconnected
                    }
                }
                return null;
            });
        }
    }

    @Subscribe
    private void onPlayerEnter(PlayerChooseInitialServerEvent event) throws SerializationException {
        if (DreamingQueue.skipPlayers.remove(event.getPlayer().getUniqueId())) return;

        if (this.handlePlayerEnter(event.getPlayer())) {
            event.setInitialServer(queueServer);
        }
    }

    @Subscribe
    private void onPlayerDisconnect(DisconnectEvent event) throws SerializationException {
        this.queuedPlayers.removeIf(p -> p.player().equals(event.getPlayer()));

        this.updateBossBars();

        Optional<ServerConnection> disconnectedFrom = event.getPlayer().getCurrentServer();
        if (disconnectedFrom.isEmpty()) {
            logger.warning("Unable to get which server the player was connected to, ignoring");
            return;
        }

        if (!disconnectedFrom.get().equals(this.queueServer)) {
            this.leftGracePlayers.put(event.getPlayer().getUniqueId(), event.getPlayer());

            int playerDifference = configHelper.getMaxPlayers() - this.targetServer.getPlayersConnected().size();

            for (int difference = playerDifference; difference > 0; difference--) {
                QueuedPlayer queuedPlayer = this.queuedPlayers.poll();
                if (queuedPlayer == null) continue;

                queuedPlayer.hideBar();
                queuedPlayer.player().createConnectionRequest(this.targetServer).connect().thenApply(result -> {
                    if (result.getStatus() != ConnectionRequestBuilder.Status.SUCCESS) {
                        try {
                            queuedPlayer.player().disconnect(Component.text("Unable to connect to server"));
                        } catch (Exception e) {
                            // Ignore disconnection error, player already disconnected
                        }
                    }
                    return null;
                });
                this.updateBossBars();
            }
        }
    }
}
