package com.linklike.server.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * 一个本地账号（对应协议里的 {@code Player} 模型）。
 *
 * <p>这里只放需要被查询、索引或管理后台直接读取的标量字段；其余标量与全部集合型状态
 * （卡片、卡组、道具、进度…）存在 {@link PlayerState} 里。字段名按 Spring 的
 * CamelCase → snake_case 策略映射到列名。
 *
 * <p>字段是 public 的：这是一个纯粹的数据载体，访问类型为字段访问，避免几十个
 * 无意义的 getter/setter。
 */
@Entity
@Table(name = "players")
public class Player {

    @Id
    @Column(name = "player_id")
    public String playerId;

    /** 旧格式玩家 ID，短 ID 迁移后仍可用它登录。 */
    public String loginAlias;

    /** 导入来源的官方账号 ID（用户中心用）。 */
    public String officialPlayerId;

    public String deviceSpecificId;

    public String playerName;

    public int playerLevel = 1;

    public String platformType = "Guest";

    /** 最近签发的会话令牌，仅作记录；真实校验走 sessions 表。 */
    public String sessionToken;

    public Long fanLevel;

    public String comment;

    /** 数据连携码（用户中心设置，account/connect 用）。 */
    public String connectSecret;

    // ---- 货币 ----
    public long jewelFree;
    public long jewelPaid;
    public long jewelPaidGoogle;
    public long musicPoint;
    public long stickerPointNum;

    // ---- 体力 ----
    public int staminaNow;
    public int staminaMax;

    /** 客户端下发的 ISO8601 原文，保持字符串避免精度失真。 */
    public String staminaRecoveryTime;

    // ---- 计数 ----
    public int friendCount;
    public int followerCount;
    public int followCount;
    public int presentBoxCount;

    // ---- 账号状态 ----
    public boolean registrationComplete;
    public boolean temporary;
    public boolean banned;
    public String banReason;

    /** 社团入口闸门（0 = 未开放，1 = 开放）。 */
    public int circleStatus;

    // ---- 未读 / 提醒标记 ----
    public boolean isAlreadyReadFriendRequest;
    public boolean isAlreadyReadShopNewArrival;
    public boolean isAlreadyReadSiscaShopNewArrival;
    public boolean isAlreadyReadMembershipNewArrival;
    public boolean isAlreadyReadPetalExchangeNewArrival;
    public boolean isAlreadyReadItemStoreNewArrival;
    public boolean isGrandPrixOpen;
    public boolean isPartyGachaUnderway;
    public boolean isCircleInviteUser;
    public boolean isCircleApproveUser;
    public boolean isCircleDissolutionUser;
    public boolean existsCircleApprovalPendingFromUser;
    public int newGachaUnreadableType;
    public long latestChatOrderId;
    public int chapterRankId;

    // ---- 本地扩展 ----
    public boolean initialGrantDone;
    public long created;
    public long lastLogin;

    public Instant createdInstant() {
        return Instant.ofEpochSecond(created);
    }

    public boolean isNewAccount() {
        return !registrationComplete;
    }
}
