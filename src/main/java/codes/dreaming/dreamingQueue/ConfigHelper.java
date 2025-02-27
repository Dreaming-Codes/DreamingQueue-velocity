package codes.dreaming.dreamingQueue;

import org.spongepowered.configurate.ConfigurationNode;
import org.spongepowered.configurate.serialize.SerializationException;
import org.spongepowered.configurate.yaml.NodeStyle;
import org.spongepowered.configurate.yaml.YamlConfigurationLoader;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Logger;

public class ConfigHelper {
    private final Logger logger;
    private final Path dataFolder;
    private ConfigurationNode configData;

    public ConfigHelper(Logger logger) {
        this.logger = logger;
        this.dataFolder = Path.of("plugins/"+DreamingQueue.PLUGIN_ID);
    }

    public void loadConfiguration() {
        try {
            if (!Files.exists(dataFolder)) {
                Files.createDirectories(dataFolder);
            }

            Path configFile = dataFolder.resolve("config.yml");
            YamlConfigurationLoader loader =
                    YamlConfigurationLoader.builder()
                            .path(configFile)
                            .nodeStyle(NodeStyle.BLOCK)
                            .build();

            if (!Files.exists(configFile)) {
                try (InputStream defaultConfigStream = this.getClass().getResourceAsStream("/config.yml")) {
                    if (defaultConfigStream != null) {
                        Files.copy(defaultConfigStream, configFile);
                    } else {
                        throw new IOException("Default config not found in resources!");
                    }
                }
            }
            configData = loader.load();
        } catch (IOException e) {
            logger.warning("Failed to load config.yml: " + e.getMessage());
        }
    }

    public String getQueueServer() throws SerializationException {
        return configData.node("queueServer").getString("queue");
    }

    public String getTargetServer() throws SerializationException {
        return configData.node("targetServer").getString("main");
    }

    public int getMaxPlayers() throws SerializationException {
        return configData.node("maxPlayers").getInt(100);
    }

    public int getGraceMinutes() throws SerializationException {
        return configData.node("graceMinutes").getInt(5);
    }

    public int getGracePriority() throws SerializationException {
        return configData.node("gracePriority").getInt(1000);
    }
    
    /**
     * Gets the priority formula from config or returns a default formula if not set.
     * Formula can include variables:
     * - %lp_priority% : Player's LuckPerms priority
     * - %online_time% : Player's online time in seconds
     * 
     * @return The formula string
     * @throws SerializationException If there's an error accessing the config
     */
    public String getPriorityFormula() throws SerializationException {
        return configData.node("priorityFormula").getString("%lp_priority% + (%online_time% / 360)");
    }
}
