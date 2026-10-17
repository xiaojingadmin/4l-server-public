package com.linklike.server.wire;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * {@link WireGrants#available()} 的表探测路径：真实 H2 + 一次连接失败重试。
 *
 * <p>覆盖 MariaDB/H2 共用的 {@code DatabaseMetaData.getTables} 分支：表不存在、表存在但为空、
 * 表有数据、以及连接异常不被缓存。
 */
class WireGrantsTest {

    private static final String DDL = """
            CREATE TABLE master_items (
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
                (Id, Name, ItemType, ItemCategory, Rarity, EffectValue, LimitNum, RequestableNum)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """;

    @Test
    void missingTableIsUnavailable() {
        assertFalse(new WireGrants(h2("grants_missing")).available());
    }

    @Test
    void emptyTableIsUnavailableAndBecomesAvailableOnceRowsExist() {
        JdbcTemplate jdbc = h2("grants_empty");
        WireGrants grants = new WireGrants(jdbc);

        jdbc.execute(DDL);
        assertFalse(grants.available(), "an empty master_items must not count as available");

        insertOneRow(jdbc);
        assertTrue(grants.available(), "a populated master_items must become available");
    }

    @Test
    void seededTableIsAvailableOnFirstProbe() {
        JdbcTemplate jdbc = h2("grants_seeded");
        jdbc.execute(DDL);
        insertOneRow(jdbc);

        assertTrue(new WireGrants(jdbc).available());
    }

    @Test
    void cachedAvailableResultShortCircuitsTheSecondProbe() {
        JdbcTemplate jdbc = h2("grants_cached");
        jdbc.execute(DDL);
        insertOneRow(jdbc);
        WireGrants grants = new WireGrants(jdbc);

        assertTrue(grants.available());
        assertTrue(grants.available(), "a cached true must short-circuit without another probe");
    }

    @Test
    void transientConnectionFailureIsNotCached() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.execute(any(ConnectionCallback.class)))
                .thenThrow(new DataAccessResourceFailureException("db down"));
        WireGrants grants = new WireGrants(jdbc);

        assertFalse(grants.available(), "a failed probe must report unavailable");

        when(jdbc.execute(any(ConnectionCallback.class))).thenReturn(true);
        when(jdbc.queryForObject(anyString(), eq(Long.class))).thenReturn(1L);

        assertTrue(grants.available(), "the next probe must retry instead of using a cached false");
        verify(jdbc, times(2)).execute(any(ConnectionCallback.class));
    }

    private static void insertOneRow(JdbcTemplate jdbc) {
        jdbc.update(INSERT, 1002001L, "Jewel", 1, 0, 0, 0, 0L, 0);
    }

    private static JdbcTemplate h2(String database) {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.h2.Driver");
        dataSource.setUrl("jdbc:h2:mem:" + database
                + ";MODE=MariaDB;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        dataSource.setUsername("sa");
        dataSource.setPassword("");
        return new JdbcTemplate(dataSource);
    }
}
