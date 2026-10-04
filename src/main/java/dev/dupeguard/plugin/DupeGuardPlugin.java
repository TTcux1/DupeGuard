package dev.dupeguard.plugin;

import dev.dupeguard.core.ActivityDetector;
import dev.dupeguard.core.AlertThrottle;
import dev.dupeguard.core.ContainerAuditor;
import dev.dupeguard.storage.FindingStore;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.time.Clock;

/**
 * Точка входа плагина. Собирает компоненты и управляет их жизненным циклом.
 * <p>
 * Детекция живёт в пакете {@code core} и не зависит от Bukkit,
 * здесь только связывание с сервером.
 */
public final class DupeGuardPlugin extends JavaPlugin {

    private static final long PURGE_PERIOD_TICKS = 20L * 60;

    private final Clock clock = Clock.systemUTC();

    private volatile PluginConfig config;
    private ContainerAuditor auditor;
    private ActivityDetector activity;
    private FindingDispatcher dispatcher;
    private FindingStore store;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        config = PluginConfig.load(getConfig(), getLogger());

        File database = new File(getDataFolder(), "findings.db");
        store = new FindingStore("jdbc:sqlite:" + database.getAbsolutePath(), getLogger());
        try {
            store.init().join();
        } catch (RuntimeException e) {
            getLogger().severe("Не удалось инициализировать базу: " + e.getMessage());
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        buildDetectors();
        dispatcher = new FindingDispatcher(store, getLogger(), config,
                new AlertThrottle(config.detection().alertCooldown(), clock));

        getServer().getPluginManager().registerEvents(new AuditListener(this), this);

        PluginCommand command = getCommand("dupeguard");
        if (command != null) {
            DupeGuardCommand executor = new DupeGuardCommand(this);
            command.setExecutor(executor);
            command.setTabCompleter(executor);
        }

        // Периодически освобождаем память от окон неактивных игроков.
        getServer().getScheduler().runTaskTimer(this, () -> activity.purgeIdle(),
                PURGE_PERIOD_TICKS, PURGE_PERIOD_TICKS);

        getLogger().info("DupeGuard запущен. Контейнеры под аудитом: " + config.auditedContainers());
    }

    @Override
    public void onDisable() {
        if (store != null) {
            store.close();
        }
    }

    /** Перечитать config.yml. Открытые сессии сбрасываются, чтобы не смешивать старые и новые пороги. */
    void reloadSettings() {
        reloadConfig();
        config = PluginConfig.load(getConfig(), getLogger());
        buildDetectors();
        dispatcher.reconfigure(config, new AlertThrottle(config.detection().alertCooldown(), clock));
    }

    private void buildDetectors() {
        auditor = new ContainerAuditor(config.detection(), clock);
        activity = new ActivityDetector(config.detection(), clock);
    }

    PluginConfig config() {
        return config;
    }

    ContainerAuditor auditor() {
        return auditor;
    }

    ActivityDetector activity() {
        return activity;
    }

    FindingDispatcher dispatcher() {
        return dispatcher;
    }

    FindingStore store() {
        return store;
    }
}
