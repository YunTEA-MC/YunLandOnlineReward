package top.yzljc.onlineReward.database;

import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.logging.Level;

public final class DatabaseManager {

    private final JavaPlugin plugin;
    private Connection connection;

    public DatabaseManager(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public void init() {
        File dataFolder = plugin.getDataFolder();
        if (!dataFolder.exists()) {
            dataFolder.mkdirs();
        }

        String dbPath = plugin.getConfig().getString("database.file", "onlinereward.db");
        File dbFile = new File(dataFolder, dbPath);

        try {
            Class.forName("org.sqlite.JDBC");
            connection = DriverManager.getConnection("jdbc:sqlite:" + dbFile.getAbsolutePath());
            // Enable WAL mode for better concurrent read/write performance
            try (Statement stmt = connection.createStatement()) {
                stmt.execute("PRAGMA journal_mode=WAL;");
                stmt.execute("PRAGMA synchronous=NORMAL;");
            }
            createTables();
            plugin.getLogger().info("数据库已连接: " + dbFile.getAbsolutePath());
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "数据库初始化失败", e);
        }
    }

    private void createTables() throws SQLException {
        try (Statement stmt = connection.createStatement()) {
            stmt.execute(
                "CREATE TABLE IF NOT EXISTS player_data (" +
                "  uuid VARCHAR(36) PRIMARY KEY," +
                "  player_name VARCHAR(32) NOT NULL," +
                "  total_online_seconds BIGINT NOT NULL DEFAULT 0," +
                "  period_online_seconds BIGINT NOT NULL DEFAULT 0," +
                "  last_join_time BIGINT NOT NULL DEFAULT 0," +
                "  last_active_time BIGINT NOT NULL DEFAULT 0," +
                "  last_reward_check_time BIGINT NOT NULL DEFAULT 0," +
                "  last_reset_time BIGINT NOT NULL DEFAULT 0" +
                ");"
            );

            stmt.execute(
                "CREATE TABLE IF NOT EXISTS claimed_rewards (" +
                "  uuid VARCHAR(36) NOT NULL," +
                "  reward_time BIGINT NOT NULL," +
                "  PRIMARY KEY (uuid, reward_time)," +
                "  FOREIGN KEY (uuid) REFERENCES player_data(uuid) ON DELETE CASCADE" +
                ");"
            );
        }
    }

    /**
     * Saves or updates a player's data to the database.
     */
    public void savePlayerData(PlayerData data) {
        if (connection == null) return;

        String sql = "INSERT OR REPLACE INTO player_data " +
                     "(uuid, player_name, total_online_seconds, period_online_seconds, last_join_time, " +
                     " last_active_time, last_reward_check_time, last_reset_time) " +
                     "VALUES (?, ?, ?, ?, ?, ?, ?, ?);";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, data.getUuid().toString());
            ps.setString(2, data.getPlayerName());
            ps.setLong(3, data.getTotalOnlineSeconds());
            ps.setLong(4, data.getPeriodOnlineSeconds());
            ps.setLong(5, data.getLastJoinTime());
            ps.setLong(6, data.getLastActiveTime());
            ps.setLong(7, data.getLastRewardCheckTime());
            ps.setLong(8, data.getLastResetTime());
            ps.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "保存玩家数据失败: " + data.getPlayerName(), e);
        }

        // Save claimed rewards
        saveClaimedRewards(data);
    }

    private void saveClaimedRewards(PlayerData data) {
        // Delete existing claimed rewards for this player, then re-insert
        String deleteSql = "DELETE FROM claimed_rewards WHERE uuid = ?;";
        String insertSql = "INSERT OR IGNORE INTO claimed_rewards (uuid, reward_time) VALUES (?, ?);";

        try {
            connection.setAutoCommit(false);
            try (PreparedStatement deletePs = connection.prepareStatement(deleteSql)) {
                deletePs.setString(1, data.getUuid().toString());
                deletePs.executeUpdate();
            }

            try (PreparedStatement insertPs = connection.prepareStatement(insertSql)) {
                for (Long rewardTime : data.getClaimedRewards()) {
                    insertPs.setString(1, data.getUuid().toString());
                    insertPs.setLong(2, rewardTime);
                    insertPs.addBatch();
                }
                insertPs.executeBatch();
            }

            connection.commit();
            connection.setAutoCommit(true);
        } catch (SQLException e) {
            try {
                connection.rollback();
                connection.setAutoCommit(true);
            } catch (SQLException ex) {
                plugin.getLogger().log(Level.SEVERE, "回滚已领取奖励数据失败", ex);
            }
            plugin.getLogger().log(Level.SEVERE, "保存已领取奖励数据失败: " + data.getPlayerName(), e);
        }
    }

    /**
     * Loads a player's data from the database, or returns null if not found.
     */
    public PlayerData loadPlayerData(UUID uuid, String playerName) {
        if (connection == null) return null;

        String sql = "SELECT * FROM player_data WHERE uuid = ?;";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    PlayerData data = new PlayerData(uuid, rs.getString("player_name"));
                    data.setTotalOnlineSeconds(rs.getLong("total_online_seconds"));
                    data.setPeriodOnlineSeconds(rs.getLong("period_online_seconds"));
                    data.setLastJoinTime(rs.getLong("last_join_time"));
                    data.setLastActiveTime(rs.getLong("last_active_time"));
                    data.setLastRewardCheckTime(rs.getLong("last_reward_check_time"));
                    data.setLastResetTime(rs.getLong("last_reset_time"));
                    data.markClean();

                    // Load claimed rewards
                    loadClaimedRewards(data);
                    return data;
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "加载玩家数据失败: " + playerName, e);
        }
        return null;
    }

    private void loadClaimedRewards(PlayerData data) {
        String sql = "SELECT reward_time FROM claimed_rewards WHERE uuid = ?;";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, data.getUuid().toString());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    data.getClaimedRewards().add(rs.getLong("reward_time"));
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "加载已领取奖励数据失败: " + data.getPlayerName(), e);
        }
    }

    /**
     * Loads all player data into a list (for bulk operations).
     */
    public List<PlayerData> loadAllPlayerData() {
        List<PlayerData> list = new ArrayList<>();
        if (connection == null) return list;

        String sql = "SELECT * FROM player_data;";
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                UUID uuid = UUID.fromString(rs.getString("uuid"));
                PlayerData data = new PlayerData(uuid, rs.getString("player_name"));
                data.setTotalOnlineSeconds(rs.getLong("total_online_seconds"));
                data.setPeriodOnlineSeconds(rs.getLong("period_online_seconds"));
                data.setLastJoinTime(rs.getLong("last_join_time"));
                data.setLastActiveTime(rs.getLong("last_active_time"));
                data.setLastRewardCheckTime(rs.getLong("last_reward_check_time"));
                data.setLastResetTime(rs.getLong("last_reset_time"));
                data.markClean();
                loadClaimedRewards(data);
                list.add(data);
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "加载全部玩家数据失败", e);
        }
        return list;
    }

    /**
     * Deletes player data for players whose last_active_time is before the given cutoff.
     * Returns the number of deleted records.
     */
    public int cleanupOldData(long cutoffTime) {
        if (connection == null) return 0;

        try {
            // Delete claimed rewards for old players first (cascade handles this)
            String sql = "DELETE FROM player_data WHERE last_active_time < ?;";
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setLong(1, cutoffTime);
                return ps.executeUpdate();
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "清理旧数据失败", e);
            return 0;
        }
    }

    /**
     * Closes the database connection.
     */
    public void close() {
        if (connection != null) {
            try {
                connection.close();
                plugin.getLogger().info("数据库连接已关闭。");
            } catch (SQLException e) {
                plugin.getLogger().log(Level.SEVERE, "关闭数据库失败", e);
            }
        }
    }
}
