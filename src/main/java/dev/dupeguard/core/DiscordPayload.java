package dev.dupeguard.core;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/** Формирует JSON для Discord-вебхука без сторонних библиотек. */
public final class DiscordPayload {

    private static final DateTimeFormatter TIME =
            DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss");

    private DiscordPayload() {
    }

    public static String build(Finding finding, String serverName, ZoneId zone) {
        String title = "[%s] %s".formatted(finding.severity(), finding.type().title());
        String description = "**Игрок:** %s\n**Где:** %s\n**Детали:** %s\n**Время:** %s".formatted(
                finding.playerName(),
                finding.location().isEmpty() ? "—" : finding.location(),
                finding.details(),
                TIME.format(finding.time().atZone(zone)));

        return "{\"username\":\"" + escape("DupeGuard · " + serverName) + "\","
                + "\"embeds\":[{"
                + "\"title\":\"" + escape(title) + "\","
                + "\"description\":\"" + escape(description) + "\","
                + "\"color\":" + colorFor(finding.severity())
                + "}]}";
    }

    static int colorFor(Severity severity) {
        return switch (severity) {
            case LOW -> 0x95A5A6;
            case MEDIUM -> 0xF1C40F;
            case HIGH -> 0xE67E22;
            case CRITICAL -> 0xE74C3C;
        };
    }

    /** Экранирование строки по RFC 8259. */
    public static String escape(String value) {
        StringBuilder sb = new StringBuilder(value.length() + 16);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                default -> {
                    if (c < 0x20) {
                        sb.append("\\u%04x".formatted((int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }
}
