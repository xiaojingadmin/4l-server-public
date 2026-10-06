-- Link! Like! Love Live! 5.1.0 服务端初始 schema。
--
-- 设计说明（相对 Python 版的重新设计）：
--   Python 版按 949 个协议模型动态生成「每个标量一列」的表，嵌套列表拆成子表。
--   这在 JPA 里无法映射，因此这里改成：
--     1. players         —— 需要被查询/索引的核心标量字段，一列一个（规范、可 SQL 查询）
--     2. player_state    —— 其余标量与全部集合型状态，按 state_key 拆成 JSON 行
--     3. ref_catalogs    —— 官方 master / 参考目录，按 catalog_key + entity_id 存 JSON
--     4. sessions        —— 会话令牌
--     5. idempotency_keys—— 商店/抽卡写入的幂等收据
--   玩家维度数据量小（本地多账号），JSON 行足以支撑，且不必随协议版本改表结构。

CREATE TABLE players (
    player_id VARCHAR(64) NOT NULL,
    login_alias VARCHAR(64) NULL,
    official_player_id VARCHAR(64) NULL,
    device_specific_id VARCHAR(64) NOT NULL DEFAULT '',
    player_name VARCHAR(64) NOT NULL DEFAULT '',
    player_level INT NOT NULL DEFAULT 1,
    platform_type VARCHAR(32) NOT NULL DEFAULT 'Guest',
    session_token VARCHAR(191) NULL,
    fan_level BIGINT NULL,
    comment VARCHAR(255) NULL,
    connect_secret VARCHAR(191) NULL,

    -- 货币
    jewel_free BIGINT NOT NULL DEFAULT 0,
    jewel_paid BIGINT NOT NULL DEFAULT 0,
    jewel_paid_google BIGINT NOT NULL DEFAULT 0,
    music_point BIGINT NOT NULL DEFAULT 0,
    sticker_point_num BIGINT NOT NULL DEFAULT 0,

    -- 体力（恢复时间保留客户端下发的 ISO8601 原文，避免纳秒精度与时区转换失真）
    stamina_now INT NOT NULL DEFAULT 0,
    stamina_max INT NOT NULL DEFAULT 0,
    stamina_recovery_time VARCHAR(40) NULL,

    -- 计数
    friend_count INT NOT NULL DEFAULT 0,
    follower_count INT NOT NULL DEFAULT 0,
    follow_count INT NOT NULL DEFAULT 0,
    present_box_count INT NOT NULL DEFAULT 0,

    -- 账号状态
    registration_complete BOOLEAN NOT NULL DEFAULT FALSE,
    temporary BOOLEAN NOT NULL DEFAULT FALSE,
    banned BOOLEAN NOT NULL DEFAULT FALSE,
    ban_reason VARCHAR(255) NULL,

    -- 首页入口闸门（0 = 维护/锁定，1 = 开放）
    circle_status INT NOT NULL DEFAULT 0,

    -- 未读 / 提醒标记
    is_already_read_friend_request BOOLEAN NOT NULL DEFAULT FALSE,
    is_already_read_shop_new_arrival BOOLEAN NOT NULL DEFAULT FALSE,
    is_already_read_sisca_shop_new_arrival BOOLEAN NOT NULL DEFAULT FALSE,
    is_already_read_membership_new_arrival BOOLEAN NOT NULL DEFAULT FALSE,
    is_already_read_petal_exchange_new_arrival BOOLEAN NOT NULL DEFAULT FALSE,
    is_already_read_item_store_new_arrival BOOLEAN NOT NULL DEFAULT FALSE,
    is_grand_prix_open BOOLEAN NOT NULL DEFAULT FALSE,
    is_party_gacha_underway BOOLEAN NOT NULL DEFAULT FALSE,
    is_circle_invite_user BOOLEAN NOT NULL DEFAULT FALSE,
    is_circle_approve_user BOOLEAN NOT NULL DEFAULT FALSE,
    is_circle_dissolution_user BOOLEAN NOT NULL DEFAULT FALSE,
    exists_circle_approval_pending_from_user BOOLEAN NOT NULL DEFAULT FALSE,
    new_gacha_unreadable_type INT NOT NULL DEFAULT 0,
    latest_chat_order_id BIGINT NOT NULL DEFAULT 0,
    chapter_rank_id INT NOT NULL DEFAULT 0,

    -- 本地扩展
    initial_grant_done BOOLEAN NOT NULL DEFAULT FALSE,
    created BIGINT NOT NULL DEFAULT 0,
    last_login BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT pk_players PRIMARY KEY (player_id)
);

CREATE INDEX idx_players_login_alias ON players (login_alias);
CREATE INDEX idx_players_official_player_id ON players (official_player_id);
CREATE INDEX idx_players_device_specific_id ON players (device_specific_id);

-- 会话令牌 -> 玩家
CREATE TABLE sessions (
    session_token VARCHAR(191) NOT NULL,
    player_id VARCHAR(64) NOT NULL,
    issued_at BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT pk_sessions PRIMARY KEY (session_token)
);

CREATE INDEX idx_sessions_player_id ON sessions (player_id);

-- 玩家状态：标量扩展 + 集合型状态，值都是 JSON
CREATE TABLE player_state (
    player_id VARCHAR(64) NOT NULL,
    state_key VARCHAR(64) NOT NULL,
    payload LONGTEXT NOT NULL,
    CONSTRAINT pk_player_state PRIMARY KEY (player_id, state_key)
);

-- 参考目录 / master 数据（官方导入或本地提取）
CREATE TABLE ref_catalogs (
    catalog_key VARCHAR(64) NOT NULL,
    entity_id VARCHAR(191) NOT NULL,
    position INT NOT NULL DEFAULT 0,
    payload LONGTEXT NOT NULL,
    CONSTRAINT pk_ref_catalogs PRIMARY KEY (catalog_key, entity_id)
);

CREATE INDEX idx_ref_catalogs_position ON ref_catalogs (catalog_key, position);

-- 商店 / 抽卡写入的幂等收据：同键重放返回原结果
CREATE TABLE idempotency_keys (
    player_id VARCHAR(64) NOT NULL,
    idem_key VARCHAR(191) NOT NULL,
    route VARCHAR(191) NOT NULL,
    request_hash CHAR(64) NOT NULL,
    response LONGTEXT NOT NULL,
    created_at BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT pk_idempotency_keys PRIMARY KEY (player_id, idem_key)
);

-- schema 迁移记录（对齐 Python 版的 schema_migrations 表，便于识别已导入数据）
CREATE TABLE schema_migrations (
    name VARCHAR(191) NOT NULL,
    CONSTRAINT pk_schema_migrations PRIMARY KEY (name)
);
