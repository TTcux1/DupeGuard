package dev.dupeguard.plugin;

import dev.dupeguard.core.ActivityDetector;
import dev.dupeguard.core.ContainerAuditor;
import dev.dupeguard.core.Finding;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.block.DoubleChest;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.BlockInventoryHolder;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

/**
 * Связывает события Bukkit с детекторами ядра.
 * <p>
 * Все обработчики стоят на приоритете MONITOR: плагин только наблюдает
 * и ничего не меняет, поэтому видит итоговое состояние после остальных плагинов.
 */
final class AuditListener implements Listener {

    static final String BYPASS_PERMISSION = "dupeguard.bypass";

    private final DupeGuardPlugin plugin;

    AuditListener(DupeGuardPlugin plugin) {
        this.plugin = plugin;
    }

    // Детекторы пересоздаются при /dupeguard reload, поэтому берём актуальные каждый раз.
    private ContainerAuditor auditor() {
        return plugin.auditor();
    }

    private ActivityDetector activity() {
        return plugin.activity();
    }

    private void dispatch(Finding finding) {
        plugin.dispatcher().dispatch(finding);
    }

    // ---------------------------------------------------------------- контейнеры

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onOpen(InventoryOpenEvent event) {
        if (!(event.getPlayer() instanceof Player player) || isExempt(player)) {
            return;
        }
        Inventory inventory = event.getInventory();
        if (!isAudited(inventory)) {
            return;
        }

        // Если контейнер уже открыт у других игроков, их сессии тоже становятся «общими».
        boolean shared = false;
        for (HumanEntity viewer : inventory.getViewers()) {
            if (!viewer.getUniqueId().equals(player.getUniqueId())) {
                shared = true;
                auditor().markShared(viewer.getUniqueId());
            }
        }

        auditor().open(player.getUniqueId(), player.getName(), inventory.getType().name(),
                locationOf(inventory, player), ItemCounter.snapshot(player, inventory), shared);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player) || !auditor().hasSession(player.getUniqueId())) {
            return;
        }
        boolean disconnected = event.getReason() == InventoryCloseEvent.Reason.DISCONNECT;
        auditor().close(player.getUniqueId(), ItemCounter.snapshot(player, event.getInventory()), disconnected)
                .ifPresent(this::dispatch);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        if (auditor().hasSession(player.getUniqueId())) {
            Inventory top = player.getOpenInventory().getTopInventory();
            auditor().close(player.getUniqueId(), ItemCounter.snapshot(player, top), true)
                    .ifPresent(this::dispatch);
        }
        activity().forget(player.getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onGameModeChange(PlayerGameModeChangeEvent event) {
        if (event.getNewGameMode() == GameMode.CREATIVE || event.getNewGameMode() == GameMode.SPECTATOR) {
            auditor().discard(event.getPlayer().getUniqueId());
        }
    }

    /** Воронка положила предмет в контейнер, который кто-то держит открытым: это легальный приход. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHopperMove(InventoryMoveItemEvent event) {
        ItemStack item = event.getItem();
        for (HumanEntity viewer : event.getDestination().getViewers()) {
            auditor().recordExternalInput(viewer.getUniqueId(), ItemCounter.key(item), item.getAmount());
        }
    }

    // ---------------------------------------------------------------- поведение

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent event) {
        if (!(event.getEntity() instanceof Player player) || isExempt(player)) {
            return;
        }
        ItemStack item = event.getItem().getItemStack();
        String key = ItemCounter.key(item);
        auditor().recordExternalInput(player.getUniqueId(), key, item.getAmount());
        activity().onPickup(player.getUniqueId(), player.getName(), key, item.getAmount(),
                        ItemCounter.describe(player.getLocation()))
                .ifPresent(this::dispatch);
    }

    /** Клики считаем даже отменённые: флуд по GUI-меню тоже показателен. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player) || isExempt(player)) {
            return;
        }
        String where = ItemCounter.describe(player.getLocation());
        activity().onClick(player.getUniqueId(), player.getName(), where).ifPresent(this::dispatch);
        checkStack(player, event.getCurrentItem(), where);
        checkStack(player, event.getCursor(), where);
    }

    private void checkStack(Player player, ItemStack item, String where) {
        if (item == null || item.getType().isAir()) {
            return;
        }
        activity().checkStack(player.getUniqueId(), player.getName(), ItemCounter.key(item),
                        item.getAmount(), item.getMaxStackSize(), where)
                .ifPresent(this::dispatch);
    }

    // ---------------------------------------------------------------- вспомогательное

    private boolean isExempt(Player player) {
        GameMode mode = player.getGameMode();
        return mode == GameMode.CREATIVE || mode == GameMode.SPECTATOR
                || player.hasPermission(BYPASS_PERMISSION);
    }

    /**
     * Проверяем только настоящие блоки-хранилища и эндер-сундук.
     * GUI-меню других плагинов тоже имеют тип CHEST, но их держатель не блок:
     * меню магазина или наград законно выдаёт предметы и дало бы ложные срабатывания.
     */
    private boolean isAudited(Inventory inventory) {
        InventoryType type = inventory.getType();
        if (!plugin.config().auditedContainers().contains(type)) {
            return false;
        }
        if (type == InventoryType.ENDER_CHEST) {
            return true;
        }
        InventoryHolder holder = inventory.getHolder(false);
        return holder instanceof BlockInventoryHolder || holder instanceof DoubleChest;
    }

    private static String locationOf(Inventory inventory, Player player) {
        Location location = inventory.getLocation();
        return ItemCounter.describe(location != null ? location : player.getLocation());
    }
}
