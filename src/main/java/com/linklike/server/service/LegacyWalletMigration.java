package com.linklike.server.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 把旧版本误写到 {@code player_state} 的 MusicPoint / StickerPointNum 合并回 players 列。
 *
 * <p>旧实现同时存在两套钱包口径：奖励写列，购买/学曲扣 state。迁移采用
 * {@code max(column, state)}，避免升级后余额凭空减少；迁移完成后删除对应 state 行，
 * 之后 {@code WireGrants} 只以 players 列为准。无法解析的单行保留原样并记录警告，交由人工处理。
 */
@Component
public class LegacyWalletMigration implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(LegacyWalletMigration.class);
    private static final Map<String, String> COLUMNS = Map.of(
            "MusicPoint", "music_point",
            "StickerPointNum", "sticker_point_num");

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public LegacyWalletMigration(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        int migrated = migrateRows();
        if (migrated > 0) {
            log.info("旧钱包 state 已迁移到 players 列：{} 行", migrated);
        }
    }

    /** 可重复调用的迁移入口；返回成功合并并删除的 state 行数。 */
    @Transactional
    public int migrate() {
        return migrateRows();
    }

    private int migrateRows() {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT player_id, state_key, payload FROM player_state "
                        + "WHERE state_key IN ('MusicPoint', 'StickerPointNum')");
        int migrated = 0;
        for (Map<String, Object> row : rows) {
            String playerId = String.valueOf(row.get("player_id"));
            String key = String.valueOf(row.get("state_key"));
            String column = COLUMNS.get(key);
            if (column == null) {
                continue;
            }
            Long value = parse(row.get("payload"));
            if (value == null) {
                log.warn("无法解析旧钱包 state：player={} key={} payload={}",
                        playerId, key, row.get("payload"));
                continue;
            }
            long legacy = Math.max(0L, value);
            if (legacy > 0) {
                jdbc.update("UPDATE players SET " + column + " = "
                                + "CASE WHEN " + column + " < ? THEN ? ELSE " + column + " END "
                                + "WHERE player_id = ?",
                        legacy, legacy, playerId);
            }
            jdbc.update("DELETE FROM player_state WHERE player_id = ? AND state_key = ?",
                    playerId, key);
            migrated++;
        }
        return migrated;
    }

    private Long parse(Object payload) {
        if (payload == null) {
            return null;
        }
        try {
            Object value = mapper.readValue(String.valueOf(payload), Object.class);
            if (value instanceof Number number) {
                return number.longValue();
            }
            if (value instanceof String text) {
                return Long.parseLong(text.trim());
            }
        } catch (Exception ignored) {
            // 由调用方记录并保留原行。
        }
        return null;
    }
}
