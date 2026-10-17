package com.linklike.server.resource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.linklike.server.config.ServerProperties;
import com.linklike.server.protocol.J;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 外部 client_defaults 目录 + 参考服务端导出的物品主表。 */
class ClientDefaultsExternalDirTest {

    @Test
    void externalFileShadowsTheClasspathCopy(@TempDir Path temp) throws IOException {
        Files.writeString(temp.resolve("card_levels.json"),
                "[{\"level\":1,\"cumulative\":999999}]");
        ClientDefaults defaults = defaults(temp);

        List<Map<String, Object>> rows = defaults.rows("card_levels");

        assertEquals(1, rows.size());
        assertEquals(999999, rows.get(0).get("cumulative"));
    }

    @Test
    void missingExternalFileFallsBackToClasspath(@TempDir Path temp) {
        ClientDefaults defaults = defaults(temp);

        List<Map<String, Object>> rows = defaults.rows("master_items");

        assertEquals(939, rows.size());
    }

    @Test
    void malformedExternalFileReturnsEmptyInsteadOfFallingBack(@TempDir Path temp)
            throws IOException {
        Files.writeString(temp.resolve("card_levels.json"), "{ not json");
        ClientDefaults defaults = defaults(temp);

        assertTrue(defaults.json("card_levels").isEmpty());
    }

    @Test
    void externalPathWithSpacesIsSupported(@TempDir Path temp) throws IOException {
        Path dir = Files.createDirectories(temp.resolve("client defaults"));
        Files.writeString(dir.resolve("master_items.json"),
                "[{\"Id\":1,\"Name\":\"Sentinel\",\"Description\":\"x\"}]");
        ClientDefaults defaults = defaults(dir);

        List<Map<String, Object>> rows = defaults.rows("master_items");

        assertEquals(1, rows.size());
        assertEquals("Sentinel", rows.get(0).get("Name"));
    }

    @Test
    void invalidExternalDirectoryFallsBackToClasspath(@TempDir Path temp) {
        ClientDefaults defaults = defaults(temp.resolve("does-not-exist"));

        assertEquals(939, defaults.rows("master_items").size());
    }

    @Test
    void fileNamesIncludeExternalFixtures(@TempDir Path temp) throws IOException {
        Files.writeString(temp.resolve("base_res_content_getHome.json"), "{}");
        ClientDefaults defaults = defaults(temp);

        assertTrue(defaults.fileNames(ClientDefaults.EXTRACTED_PREFIX)
                .contains("base_res_content_getHome"));
    }

    @Test
    void bundledMasterItemsHaveUniqueIds() {
        ClientDefaults defaults = new ClientDefaults(new ObjectMapper(), new ServerProperties());

        List<Map<String, Object>> rows = defaults.rows("master_items");
        Set<Long> ids = new HashSet<>();
        for (Map<String, Object> row : rows) {
            Long id = J.asLong(row.get("Id"));
            assertNotNull(id, "master_items row has a non-numeric Id");
            ids.add(id);
        }

        assertEquals(939, rows.size());
        assertEquals(939, ids.size());
    }

    private static ClientDefaults defaults(Path dir) {
        ServerProperties config = new ServerProperties();
        config.setClientDefaultsDir(dir.toString());
        return new ClientDefaults(new ObjectMapper(), config);
    }
}
