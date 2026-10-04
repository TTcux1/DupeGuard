package dev.dupeguard.plugin;

import dev.dupeguard.core.ItemCounts;
import org.bukkit.Location;
import org.bukkit.block.BlockState;
import org.bukkit.block.ShulkerBox;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.inventory.meta.BundleMeta;
import org.bukkit.inventory.meta.ItemMeta;

/**
 * Переводит инвентари Bukkit в {@link ItemCounts}.
 * <p>
 * Учитывает содержимое шалкеров и мешков внутри инвентаря: многие дюпы
 * размножают именно заполненные шалкеры, и без этого прирост был бы незаметен.
 */
final class ItemCounter {

    /** Шалкер в мешке в шалкере — глубже в ванилле не бывает. */
    private static final int MAX_DEPTH = 3;

    private ItemCounter() {
    }

    /** Инвентарь игрока (включая броню и вторую руку) + предмет на курсоре + открытый контейнер. */
    static ItemCounts snapshot(Player player, Inventory container) {
        ItemCounts counts = new ItemCounts();
        countAll(player.getInventory().getContents(), counts, 0);
        countItem(player.getItemOnCursor(), counts, 0);
        if (container != null) {
            countAll(container.getContents(), counts, 0);
        }
        return counts;
    }

    /** Ключ предмета для статистики: имя материала. */
    static String key(ItemStack item) {
        return item.getType().name();
    }

    static String describe(Location location) {
        if (location == null || location.getWorld() == null) {
            return "";
        }
        return "%s %d %d %d".formatted(location.getWorld().getName(),
                location.getBlockX(), location.getBlockY(), location.getBlockZ());
    }

    private static void countAll(ItemStack[] items, ItemCounts counts, int depth) {
        if (items == null) {
            return;
        }
        for (ItemStack item : items) {
            countItem(item, counts, depth);
        }
    }

    private static void countItem(ItemStack item, ItemCounts counts, int depth) {
        if (item == null || item.getType().isAir() || item.getAmount() <= 0) {
            return;
        }
        counts.add(key(item), item.getAmount());

        if (depth >= MAX_DEPTH || !item.hasItemMeta()) {
            return;
        }
        ItemMeta meta = item.getItemMeta();
        long multiplier = item.getAmount();

        if (meta instanceof BlockStateMeta stateMeta && stateMeta.hasBlockState()) {
            BlockState state = stateMeta.getBlockState();
            if (state instanceof ShulkerBox box) {
                ItemCounts nested = new ItemCounts();
                countAll(box.getInventory().getContents(), nested, depth + 1);
                addMultiplied(counts, nested, multiplier);
            }
        } else if (meta instanceof BundleMeta bundle && bundle.hasItems()) {
            ItemCounts nested = new ItemCounts();
            countAll(bundle.getItems().toArray(new ItemStack[0]), nested, depth + 1);
            addMultiplied(counts, nested, multiplier);
        }
    }

    /** Заполненные шалкеры не стакаются, но множитель оставлен на случай модов и кастомных правил. */
    private static void addMultiplied(ItemCounts target, ItemCounts nested, long multiplier) {
        nested.asMap().forEach((key, amount) -> target.add(key, amount * multiplier));
    }
}
