package com.linklike.server.resource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.linklike.server.config.ServerProperties;
import com.linklike.server.protocol.J;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** 随仓库分发的数据必须完整，运行时不依赖外部参考服务端。 */
class BundledDataTest {

    @Test
    void extractedClientDefaultsAreOnTheClasspath() {
        ClientDefaults defaults = new ClientDefaults(new ObjectMapper(), new ServerProperties());

        List<String> names = defaults.fileNames(ClientDefaults.EXTRACTED_PREFIX).stream()
                .filter(name -> !name.endsWith(".source"))
                .toList();

        assertEquals(20, names.size());
    }

    @Test
    void bothItemMasterVersionsAreBundled() {
        ClientDefaults defaults = new ClientDefaults(new ObjectMapper(), new ServerProperties());

        assertEquals(939, defaults.rows("master_items_5.1.0").size());
        assertEquals(939, defaults.rows("master_items").size());
        assertTrue(defaults.rows("master_items_6.0.0").size() > 900);
        assertEquals(uniqueIds(defaults.rows("master_items_5.1.0")), 939);
        assertEquals(uniqueIds(defaults.rows("master_items_6.0.0")),
                defaults.rows("master_items_6.0.0").size());
    }

    @Test
    void resourceCatalogsAndMasterDatabasesAreBundled() {
        assertTrue(Files.isRegularFile(
                Path.of("build/catalog/5.1.0-R2604100/catalog_entries.json")));
        assertTrue(Files.isRegularFile(
                Path.of("build/catalog/6.0.0-R2606100/catalog_entries.json")));
        assertTrue(Files.isRegularFile(Path.of("data/master_5.1.0.db")));
        assertTrue(Files.isRegularFile(Path.of("data/cards.db")));
        assertTrue(Files.isRegularFile(Path.of("data/master_items_5.1.0.json")));
        assertTrue(Files.isRegularFile(Path.of("data/master_items_6.0.0.json")));
    }

    private static int uniqueIds(List<Map<String, Object>> rows) {
        Set<Long> ids = new HashSet<>();
        for (Map<String, Object> row : rows) {
            ids.add(J.asLong(row.get("Id")));
        }
        return ids.size();
    }
}
