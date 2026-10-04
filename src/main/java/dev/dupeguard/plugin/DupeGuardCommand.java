package dev.dupeguard.plugin;

import dev.dupeguard.core.Finding;
import dev.dupeguard.core.FindingType;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** /dupeguard alerts | lookup &lt;ник&gt; [кол-во] | stats | reload */
final class DupeGuardCommand implements TabExecutor {

    private static final String LOOKUP_PERMISSION = "dupeguard.lookup";
    private static final String ADMIN_PERMISSION = "dupeguard.admin";
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("dd.MM HH:mm:ss")
            .withZone(ZoneId.systemDefault());
    private static final int MAX_LOOKUP = 50;

    private final DupeGuardPlugin plugin;

    DupeGuardCommand(DupeGuardPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sendUsage(sender, label);
            return true;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "alerts" -> toggleAlerts(sender);
            case "lookup" -> lookup(sender, label, args);
            case "stats" -> stats(sender);
            case "reload" -> reload(sender);
            default -> sendUsage(sender, label);
        }
        return true;
    }

    private void toggleAlerts(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("Команда только для игроков.", NamedTextColor.RED));
            return;
        }
        if (!player.hasPermission(FindingDispatcher.ALERT_PERMISSION)) {
            noPermission(sender);
            return;
        }
        boolean enabled = plugin.dispatcher().toggleAlerts(player.getUniqueId());
        player.sendMessage(Component.text("Алерты DupeGuard " + (enabled ? "включены" : "выключены"),
                enabled ? NamedTextColor.GREEN : NamedTextColor.YELLOW));
    }

    private void lookup(CommandSender sender, String label, String[] args) {
        if (!sender.hasPermission(LOOKUP_PERMISSION)) {
            noPermission(sender);
            return;
        }
        if (args.length < 2) {
            sender.sendMessage(Component.text("Использование: /" + label + " lookup <ник> [кол-во]",
                    NamedTextColor.RED));
            return;
        }
        String name = args[1];
        int limit = 10;
        if (args.length >= 3) {
            try {
                limit = Math.min(MAX_LOOKUP, Math.max(1, Integer.parseInt(args[2])));
            } catch (NumberFormatException e) {
                sender.sendMessage(Component.text("Количество должно быть числом.", NamedTextColor.RED));
                return;
            }
        }

        plugin.store().recent(name, limit).whenComplete((findings, error) ->
                // Ответ отправляем из главного потока сервера.
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (error != null) {
                        plugin.getLogger().warning("Ошибка lookup: " + error.getMessage());
                        sender.sendMessage(Component.text("Ошибка чтения базы, см. консоль.", NamedTextColor.RED));
                        return;
                    }
                    printLookup(sender, name, findings);
                }));
    }

    private static void printLookup(CommandSender sender, String name, List<Finding> findings) {
        if (findings.isEmpty()) {
            sender.sendMessage(Component.text("По игроку " + name + " находок нет.", NamedTextColor.GREEN));
            return;
        }
        sender.sendMessage(Component.text("Последние находки по " + name + ":", NamedTextColor.GOLD));
        for (Finding f : findings) {
            sender.sendMessage(Component.text(TIME.format(f.time()) + " ", NamedTextColor.DARK_GRAY)
                    .append(Component.text("[" + f.severity() + "] ", FindingDispatcher.colorFor(f.severity())))
                    .append(Component.text(f.type().title() + ": ", NamedTextColor.GRAY))
                    .append(Component.text(f.details(), NamedTextColor.WHITE))
                    .append(Component.text(f.location().isEmpty() ? "" : " @ " + f.location(),
                            NamedTextColor.DARK_GRAY)));
        }
    }

    private void stats(CommandSender sender) {
        if (!sender.hasPermission(LOOKUP_PERMISSION)) {
            noPermission(sender);
            return;
        }
        Map<FindingType, Integer> stats = plugin.dispatcher().stats();
        sender.sendMessage(Component.text("DupeGuard — с момента запуска:", NamedTextColor.GOLD));
        sender.sendMessage(Component.text("Открытых сессий контейнеров: " + plugin.auditor().activeSessions(),
                NamedTextColor.GRAY));
        for (FindingType type : FindingType.values()) {
            sender.sendMessage(Component.text(type.title() + ": " + stats.getOrDefault(type, 0),
                    NamedTextColor.GRAY));
        }
    }

    private void reload(CommandSender sender) {
        if (!sender.hasPermission(ADMIN_PERMISSION)) {
            noPermission(sender);
            return;
        }
        plugin.reloadSettings();
        sender.sendMessage(Component.text("Конфигурация DupeGuard перезагружена.", NamedTextColor.GREEN));
    }

    private static void sendUsage(CommandSender sender, String label) {
        sender.sendMessage(Component.text("DupeGuard — мониторинг подозрительных операций с предметами",
                NamedTextColor.GOLD));
        sender.sendMessage(Component.text("/" + label + " alerts — вкл/выкл алерты", NamedTextColor.GRAY));
        sender.sendMessage(Component.text("/" + label + " lookup <ник> [кол-во] — история игрока",
                NamedTextColor.GRAY));
        sender.sendMessage(Component.text("/" + label + " stats — статистика", NamedTextColor.GRAY));
        sender.sendMessage(Component.text("/" + label + " reload — перезагрузить конфиг", NamedTextColor.GRAY));
    }

    private static void noPermission(CommandSender sender) {
        sender.sendMessage(Component.text("Недостаточно прав.", NamedTextColor.RED));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> result = new ArrayList<>();
        if (args.length == 1) {
            for (String sub : List.of("alerts", "lookup", "stats", "reload")) {
                if (sub.startsWith(args[0].toLowerCase(Locale.ROOT))) {
                    result.add(sub);
                }
            }
        } else if (args.length == 2 && args[0].equalsIgnoreCase("lookup")) {
            String prefix = args[1].toLowerCase(Locale.ROOT);
            for (Player online : Bukkit.getOnlinePlayers()) {
                if (online.getName().toLowerCase(Locale.ROOT).startsWith(prefix)) {
                    result.add(online.getName());
                }
            }
        }
        return result;
    }
}
