package com.linklike.server.config;

import com.linklike.server.protocol.J;
import com.linklike.server.resource.ClientDefaults;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 启动时把随仓库分发的物品主表写入 {@code master_items}（MariaDB / H2 都适用）。
 *
 * <p>默认 MariaDB 路径如果已经有 Python 侧导入的 {@code master_items}，这里直接跳过；
 * 如果表不存在或为空，则从 {@code client_defaults/master_items_<clientVersion>.json}
 * 建表并批量写入。这样 public 目录自带物品数据，物品详情与节奏游戏发奖不再依赖
 * Python 侧的 master 提取步骤。
 *
 * <p>其它 master_* 表仍需要完整的 MariaDB 导入；H2 模式下未导入的领域接口按既有的
 * 501/空目录行为处理。
 */
@Component
public class MasterItemsSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(MasterItemsSeeder.class);

    private static final String CREATE_TABLE = """
            CREATE TABLE IF NOT EXISTS master_items (
                Id BIGINT PRIMARY KEY,
                Name VARCHAR(512),
                NameFurigana VARCHAR(512),
                ItemType INT,
                ItemCategory INT,
                Rarity INT,
                EffectValue INT,
                LimitNum BIGINT,
                RequestableNum INT,
                Description VARCHAR(8192),
                StartTime VARCHAR(64),
                EndTime VARCHAR(64)
            )
            """;

    private static final String INSERT = """
            INSERT INTO master_items
                (Id, Name, NameFurigana, ItemType, ItemCategory, Rarity,
                 EffectValue, LimitNum, RequestableNum, Description, StartTime, EndTime)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

    private final JdbcTemplate jdbc;
    private final ClientDefaults defaults;
    private final ServerProperties config;

    public MasterItemsSeeder(JdbcTemplate jdbc, ClientDefaults defaults,
                             ServerProperties config) {
        this.jdbc = jdbc;
        this.defaults = defaults;
        this.config = config;
    }

    @Override
    public void run(ApplicationArguments args) {
        jdbc.execute(CREATE_TABLE);
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM master_items", Long.class);
        if (count != null && count > 0) {
            log.info("master_items 已有 {} 行，跳过静态主表导入", count);
            return;
        }

        String version = config.getClientVersion();
        List<Map<String, Object>> rows = defaults.rows("master_items_" + version);
        if (rows.isEmpty() && !"5.1.0".equals(version)) {
            rows = defaults.rows("master_items");
        }
        if (rows.isEmpty()) {
            log.warn("H2 模式没有可导入的 master_items JSON（client-version={}）", version);
            return;
        }

        List<Object[]> batch = new ArrayList<>(rows.size());
        int skipped = 0;
        for (Map<String, Object> row : rows) {
            Long id = J.asLong(row.get("Id"));
            if (id == null) {
                skipped++;
                continue;
            }
            batch.add(new Object[]{
                    id,
                    J.str(row.get("Name")),
                    J.str(row.get("NameFurigana")),
                    J.intOr(row.get("ItemType"), 0),
                    J.intOr(row.get("ItemCategory"), 0),
                    J.intOr(row.get("Rarity"), 0),
                    J.intOr(row.get("EffectValue"), 0),
                    J.longOr(row.get("LimitNum"), 0),
                    J.intOr(row.get("RequestableNum"), 0),
                    J.str(row.get("Description")),
                    J.str(row.get("StartTime")),
                    J.str(row.get("EndTime"))
            });
        }
        jdbc.batchUpdate(INSERT, batch);
        log.info("master_items 已从 {} 导入 {} 行（跳过 {} 行无效 Id）",
                "master_items_" + version, batch.size(), skipped);
    }
}
