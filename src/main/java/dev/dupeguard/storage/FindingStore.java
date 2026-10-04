package dev.dupeguard.storage;

import dev.dupeguard.core.Finding;
import dev.dupeguard.core.FindingType;
import dev.dupeguard.core.Severity;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Хранилище находок в SQLite.
 * <p>
 * Все обращения к базе выполняются в одном отдельном потоке, чтобы не нагружать
 * главный поток сервера и не держать пул соединений ради одного файла.
 * Драйвер SQLite уже есть внутри Paper/Spigot, отдельная зависимость не нужна.
 */
public final class FindingStore implements AutoCloseable {

    private final String jdbcUrl;
    private final Logger logger;
    private final ExecutorService executor;
    private Connection connection;

    public FindingStore(String jdbcUrl, Logger logger) {
        this.jdbcUrl = jdbcUrl;
        this.logger = logger;
        this.executor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "DupeGuard-DB");
            thread.setDaemon(true);
            return thread;
        });
    }

    public CompletableFuture<Void> init() {
        return CompletableFuture.runAsync(() -> {
            try {
                connection = DriverManager.getConnection(jdbcUrl);
                try (Statement st = connection.createStatement()) {
                    st.execute("PRAGMA journal_mode=WAL");
                    st.execute("""
                            CREATE TABLE IF NOT EXISTS findings (
                                id             INTEGER PRIMARY KEY AUTOINCREMENT,
                                created_at     INTEGER NOT NULL,
                                player_uuid    TEXT    NOT NULL,
                                player_name    TEXT    NOT NULL,
                                player_name_lc TEXT    NOT NULL,
                                type           TEXT    NOT NULL,
                                severity       TEXT    NOT NULL,
                                location       TEXT    NOT NULL,
                                details        TEXT    NOT NULL
                            )""");
                    st.execute("CREATE INDEX IF NOT EXISTS idx_findings_player "
                            + "ON findings (player_name_lc, created_at DESC)");
                }
            } catch (SQLException e) {
                throw new IllegalStateException("Не удалось открыть базу " + jdbcUrl, e);
            }
        }, executor);
    }

    public CompletableFuture<Void> save(Finding finding) {
        return CompletableFuture.runAsync(() -> {
            String sql = "INSERT INTO findings (created_at, player_uuid, player_name, player_name_lc, "
                    + "type, severity, location, details) VALUES (?, ?, ?, ?, ?, ?, ?, ?)";
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setLong(1, finding.time().toEpochMilli());
                ps.setString(2, finding.playerId().toString());
                ps.setString(3, finding.playerName());
                ps.setString(4, finding.playerName().toLowerCase(Locale.ROOT));
                ps.setString(5, finding.type().name());
                ps.setString(6, finding.severity().name());
                ps.setString(7, finding.location());
                ps.setString(8, finding.details());
                ps.executeUpdate();
            } catch (SQLException e) {
                logger.log(Level.WARNING, "Не удалось сохранить находку", e);
            }
        }, executor);
    }

    /** Последние находки по нику, от новых к старым. */
    public CompletableFuture<List<Finding>> recent(String playerName, int limit) {
        return CompletableFuture.supplyAsync(() -> {
            String sql = "SELECT created_at, player_uuid, player_name, type, severity, location, details "
                    + "FROM findings WHERE player_name_lc = ? ORDER BY created_at DESC LIMIT ?";
            List<Finding> result = new ArrayList<>();
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setString(1, playerName.toLowerCase(Locale.ROOT));
                ps.setInt(2, limit);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        result.add(new Finding(
                                Instant.ofEpochMilli(rs.getLong("created_at")),
                                UUID.fromString(rs.getString("player_uuid")),
                                rs.getString("player_name"),
                                FindingType.valueOf(rs.getString("type")),
                                Severity.valueOf(rs.getString("severity")),
                                rs.getString("location"),
                                rs.getString("details")));
                    }
                }
            } catch (SQLException e) {
                throw new IllegalStateException("Ошибка чтения из базы", e);
            }
            return result;
        }, executor);
    }

    @Override
    public void close() {
        executor.submit(() -> {
            if (connection != null) {
                try {
                    connection.close();
                } catch (SQLException e) {
                    logger.log(Level.WARNING, "Ошибка при закрытии базы", e);
                }
            }
        });
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                logger.warning("База не успела закрыться за 5 секунд");
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            executor.shutdownNow();
        }
    }
}
