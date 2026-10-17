package com.linklike.server.config;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.linklike.server.resource.ClientDefaults;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.ApplicationArguments;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * {@link MasterItemsSeeder} 的幂等与无效数据路径。
 *
 * <p>纯 Mockito：不起 Spring 上下文、不碰数据库和文件系统，只验证 seeder 对
 * {@link JdbcTemplate} / {@link ClientDefaults} 的调用形状。
 */
class MasterItemsSeederTest {

    private static final String COUNT_SQL = "SELECT COUNT(*) FROM master_items";
    private static final String ROWS_NAME = "master_items_5.1.0";

    private JdbcTemplate jdbc;
    private ClientDefaults defaults;
    private ServerProperties config;
    private ApplicationArguments args;
    private MasterItemsSeeder seeder;

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        defaults = mock(ClientDefaults.class);
        config = new ServerProperties();
        config.setClientVersion("5.1.0");
        args = mock(ApplicationArguments.class);
        seeder = new MasterItemsSeeder(jdbc, defaults, config);
    }

    @Test
    void skipsImportWhenTableAlreadyHasRows() {
        when(jdbc.queryForObject(COUNT_SQL, Long.class)).thenReturn(939L);

        seeder.run(args);

        verify(jdbc, never()).batchUpdate(anyString(), anyList());
        verify(defaults, never()).rows(anyString());
    }

    @Test
    void doesNotThrowOrWriteWhenJsonHasNoUsableRows() {
        when(jdbc.queryForObject(COUNT_SQL, Long.class)).thenReturn(0L);
        when(defaults.rows(ROWS_NAME)).thenReturn(List.of());
        when(defaults.rows("master_items")).thenReturn(List.of());

        assertDoesNotThrow(() -> seeder.run(args));

        verify(jdbc, never()).batchUpdate(anyString(), anyList());
    }

    @Test
    @SuppressWarnings("unchecked")
    void skipsRowsWithoutIdAndWritesTheValidOnes() {
        when(jdbc.queryForObject(COUNT_SQL, Long.class)).thenReturn(0L);
        when(defaults.rows(ROWS_NAME)).thenReturn(List.of(validRow(), rowWithoutId()));

        seeder.run(args);

        ArgumentCaptor<List<Object[]>> captor = ArgumentCaptor.forClass(List.class);
        verify(jdbc, times(1)).batchUpdate(anyString(), captor.capture());
        List<Object[]> batch = captor.getValue();
        assertEquals(1, batch.size(), "only the row with an Id must be written");
        Object[] written = batch.get(0);
        assertEquals(12, written.length);
        assertEquals(1001L, written[0], "Id must be the first bind parameter");
        assertEquals("Potion", written[1], "Name must be the second bind parameter");
    }

    @Test
    @SuppressWarnings("unchecked")
    void fallsBackToTheBaseItemMasterWhenTheVersionedFileIsMissing() {
        config.setClientVersion("6.0.0");
        when(jdbc.queryForObject(COUNT_SQL, Long.class)).thenReturn(0L);
        when(defaults.rows("master_items_6.0.0")).thenReturn(List.of());
        when(defaults.rows("master_items")).thenReturn(List.of(validRow()));

        seeder.run(args);

        ArgumentCaptor<List<Object[]>> captor = ArgumentCaptor.forClass(List.class);
        verify(jdbc, times(1)).batchUpdate(anyString(), captor.capture());
        assertEquals(1, captor.getValue().size());
        assertEquals(1001L, captor.getValue().get(0)[0]);
    }

    @Test
    @SuppressWarnings("unchecked")
    void allInvalidRowsProduceAnEmptyBatchWithoutThrowing() {
        when(jdbc.queryForObject(COUNT_SQL, Long.class)).thenReturn(0L);
        when(defaults.rows(ROWS_NAME)).thenReturn(List.of(rowWithoutId()));

        assertDoesNotThrow(() -> seeder.run(args));

        ArgumentCaptor<List<Object[]>> captor = ArgumentCaptor.forClass(List.class);
        verify(jdbc, times(1)).batchUpdate(anyString(), captor.capture());
        assertTrue(captor.getValue().isEmpty(), "no invalid row may reach the batch");
    }

    private static Map<String, Object> validRow() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("Id", 1001);
        row.put("Name", "Potion");
        return row;
    }

    private static Map<String, Object> rowWithoutId() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("Name", "Missing Id");
        return row;
    }
}
