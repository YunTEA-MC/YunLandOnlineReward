package top.yzljc.onlineReward.database;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

public final class PlayerData {

    private final UUID uuid;
    private String playerName;
    /** 累计总在线时长（秒），不会重置 */
    private long totalOnlineSeconds;
    /** 当前奖励周期内的在线时长（秒），随每日/每周重置而清零 */
    private long periodOnlineSeconds;
    private long lastJoinTime;
    private long lastActiveTime;
    private long lastRewardCheckTime;

    /** 已领取的奖励时间阈值集合 */
    private final Set<Long> claimedRewards;

    /** 上次奖励重置的时间戳（epoch millis），用于判断是否跨日/跨周 */
    private long lastResetTime;

    /** 数据是否已修改，用于判断是否需要写入SQL */
    private transient boolean dirty;

    public PlayerData(UUID uuid, String playerName) {
        this.uuid = uuid;
        this.playerName = playerName;
        this.totalOnlineSeconds = 0;
        this.periodOnlineSeconds = 0;
        this.lastJoinTime = System.currentTimeMillis();
        this.lastActiveTime = System.currentTimeMillis();
        this.lastRewardCheckTime = System.currentTimeMillis();
        this.claimedRewards = new HashSet<>();
        this.lastResetTime = System.currentTimeMillis();
        this.dirty = true;
    }

    public UUID getUuid() {
        return uuid;
    }

    public String getPlayerName() {
        return playerName;
    }

    public void setPlayerName(String playerName) {
        this.playerName = playerName;
        markDirty();
    }

    public long getTotalOnlineSeconds() {
        return totalOnlineSeconds;
    }

    public void setTotalOnlineSeconds(long totalOnlineSeconds) {
        this.totalOnlineSeconds = totalOnlineSeconds;
        markDirty();
    }

    public void addOnlineSeconds(long seconds) {
        this.totalOnlineSeconds += seconds;
        this.periodOnlineSeconds += seconds;
        markDirty();
    }

    public long getPeriodOnlineSeconds() {
        return periodOnlineSeconds;
    }

    public void setPeriodOnlineSeconds(long periodOnlineSeconds) {
        this.periodOnlineSeconds = periodOnlineSeconds;
        markDirty();
    }

    public long getLastJoinTime() {
        return lastJoinTime;
    }

    public void setLastJoinTime(long lastJoinTime) {
        this.lastJoinTime = lastJoinTime;
        markDirty();
    }

    public long getLastActiveTime() {
        return lastActiveTime;
    }

    public void setLastActiveTime(long lastActiveTime) {
        this.lastActiveTime = lastActiveTime;
    }

    public long getLastRewardCheckTime() {
        return lastRewardCheckTime;
    }

    public void setLastRewardCheckTime(long lastRewardCheckTime) {
        this.lastRewardCheckTime = lastRewardCheckTime;
    }

    public Set<Long> getClaimedRewards() {
        return claimedRewards;
    }

    public boolean hasClaimedReward(long timeThreshold) {
        return claimedRewards.contains(timeThreshold);
    }

    public void markRewardClaimed(long timeThreshold) {
        claimedRewards.add(timeThreshold);
        markDirty();
    }

    public void clearClaimedRewards() {
        claimedRewards.clear();
        markDirty();
    }

    public long getLastResetTime() {
        return lastResetTime;
    }

    public void setLastResetTime(long lastResetTime) {
        this.lastResetTime = lastResetTime;
        markDirty();
    }

    public boolean isDirty() {
        return dirty;
    }

    public void markDirty() {
        this.dirty = true;
    }

    public void markClean() {
        this.dirty = false;
    }

    /**
     * Calculates the session online time (since last join) in seconds.
     */
    public long getSessionOnlineSeconds() {
        if (lastJoinTime <= 0) return 0;
        return (System.currentTimeMillis() - lastJoinTime) / 1000;
    }
}
