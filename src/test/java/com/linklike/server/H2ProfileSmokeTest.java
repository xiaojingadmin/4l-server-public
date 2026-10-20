package com.linklike.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.linklike.server.protocol.J;
import com.linklike.server.protocol.WireError;
import com.linklike.server.service.PlayerContext;
import com.linklike.server.service.PlayerStore;
import com.linklike.server.web.WireGateway;
import com.linklike.server.wire.WireGrants;
import com.linklike.server.wire.WireHandler;
import com.linklike.server.wire.WireRequest;
import com.linklike.server.wire.WireRouter;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/** 只用 public 目录内的 H2 文件库启动；不依赖 MariaDB。 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.datasource.url=jdbc:h2:mem:linklike-test;MODE=MariaDB;"
                        + "DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
                "spring.datasource.username=sa",
                "spring.datasource.password=",
                "spring.datasource.driver-class-name=org.h2.Driver",
                "spring.jpa.hibernate.ddl-auto=update",
                "spring.flyway.enabled=false",
                "linklike.log-requests=false"
        })
@ActiveProfiles("h2")
class H2ProfileSmokeTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private WireGrants grants;

    @Autowired
    private WireRouter router;

    @Autowired
    private ObjectMapper mapper;

    @Autowired
    private com.linklike.server.config.ServerProperties config;

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private PlayerStore store;

    @Autowired
    private WireGateway gateway;

    @Test
    void h2SeedsMasterItemsAndGrantsAreAvailable() {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM master_items", Long.class);
        assertNotNull(count);
        assertEquals(939L, count);
        assertTrue(grants.available());
        assertTrue(router.supports("/v1/register/getterms"),
                "router paths: " + router.paths());
    }

    @Test
    void everySnapshotRouteHasAHandler() {
        assertEquals(Set.of(), router.pending(),
                "仍是快照兜底 501 的路径：" + router.pending());
    }

    @Test
    void getTermsHandlerIsImplemented() {
        WireHandler handler = router.resolve("/v1/register/getterms");
        assertNotNull(handler);
        Map<String, Object> out = asMap(handler.handle(new WireRequest(
                "/v1/register/getterms", Map.of(), Map.of(), null, null, mapper, config)));
        assertTrue(String.valueOf(out.get("terms")).contains("Local development server"));
    }

    @Test
    void healthAndTermsWorkWithoutMariaDb() {
        ResponseEntity<Map> health = rest.getForEntity("/health", Map.class);
        assertEquals(HttpStatus.OK, health.getStatusCode());
        assertEquals("ok", health.getBody().get("status"));

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<String> terms = rest.postForEntity(
                "/v1/register/getterms", new HttpEntity<>("{}", headers), String.class);
        System.out.println("TERMS RESPONSE " + terms.getStatusCode() + " " + terms.getBody());
        assertEquals(HttpStatus.OK, terms.getStatusCode());
        assertTrue(terms.getBody().contains("terms"));
    }

    @Test
    void missionGetListDerivesProgressWithoutPersistingTheRead() {
        String token = "mission-read-token";
        PlayerContext player = store.createGuest("Mission Reader", token);
        player.put("SchoolStageStar", 2526);
        player.put("Cards", new ArrayList<>());
        store.save(player);

        WireGateway.Result result = gateway.dispatch("/v1/mission/get_list", Map.of(),
                Map.of("authorization", "Bearer " + token));

        List<Map<String, Object>> groups = J.rowsOr(asMap(result.body()).get("mission_list"));
        assertEquals(30, missionRow(groups, 30100001).get("achieved_num"));

        PlayerContext reloaded = store.loadById(player.id());
        assertNotNull(reloaded);
        assertFalse(reloaded.has("service_missions"),
                "mission/get_list 是纯读，派生进度不能落库");
    }

    @Test
    void stalePlayerSnapshotsAreRejectedInsteadOfOverwriting() {
        PlayerContext created = store.createGuest("Concurrent Writer", "concurrent-token");
        String ident = created.id();
        PlayerContext first = store.loadById(ident);
        PlayerContext second = store.loadById(ident);
        assertNotNull(first);
        assertNotNull(second);

        first.entity().jewelFree = 100;
        store.save(first);
        second.entity().jewelFree = 200;

        WireError error = assertThrows(WireError.class, () -> store.save(second));
        assertEquals(409, error.status());
        assertEquals(100L, store.loadById(ident).entity().jewelFree);
    }

    @Test
    void staleStateOnlySnapshotsAreRejectedToo() {
        PlayerContext created = store.createGuest("State Writer", "state-concurrent-token");
        String ident = created.id();
        PlayerContext first = store.loadById(ident);
        PlayerContext second = store.loadById(ident);
        assertNotNull(first);
        assertNotNull(second);

        first.put("Cards", new ArrayList<>(List.of(Map.of("CardDatasId", 10104000))));
        store.save(first);
        second.put("Cards", new ArrayList<>(List.of(Map.of("CardDatasId", 10104001))));

        WireError error = assertThrows(WireError.class, () -> store.save(second));
        assertEquals(409, error.status());
        assertEquals(1, store.loadById(ident).rowsSnapshot("Cards").size());
    }

    @Test
    void idempotencyTableRejectsAConcurrentDuplicateKey() {
        PlayerContext player = store.createGuest("Idempotent Writer", "idem-token");
        store.saveWithIdempotency(player, "same-key", "/v1/test", "hash",
                Map.of("result", true));

        WireError error = assertThrows(WireError.class, () -> store.saveWithIdempotency(
                player, "same-key", "/v1/test", "hash", Map.of("result", true)));
        assertEquals(409, error.status());
    }

    @Test
    void failedStateSaveRollsBackItsIdempotencyKey() {
        PlayerContext created = store.createGuest("Rollback Writer", "idem-rollback-token");
        PlayerContext stale = store.loadById(created.id());
        PlayerContext winner = store.loadById(created.id());
        assertNotNull(stale);
        assertNotNull(winner);
        winner.entity().jewelFree = 1;
        store.save(winner);

        stale.put("Cards", new ArrayList<>(List.of(Map.of("CardDatasId", 10104000))));
        WireError error = assertThrows(WireError.class, () -> store.saveWithIdempotency(
                stale, "rolled-back-key", "/v1/test", "hash", Map.of("result", true)));
        assertEquals(409, error.status());
        assertEquals(0L, jdbc.queryForObject(
                "SELECT COUNT(*) FROM idempotency_keys WHERE player_id = ? AND idem_key = ?",
                Long.class, created.id(), "rolled-back-key"));
    }

    @Test
    void unchangedSavesDoNotBumpTheVersionOrConflict() {
        PlayerContext created = store.createGuest("Read Only Writer", "unchanged-token");
        PlayerContext first = store.loadById(created.id());
        PlayerContext second = store.loadById(created.id());
        assertNotNull(first);
        assertNotNull(second);
        long version = first.entity().version;

        store.save(first);
        store.save(second);

        assertEquals(version, store.loadById(created.id()).entity().version);
    }

    @Test
    void saveAllRollsBackEveryPlayerWhenOneFails() {
        PlayerContext created = store.createGuest("Pair Writer", "pair-token");
        PlayerContext stale = store.loadById(created.id());
        PlayerContext winner = store.loadById(created.id());
        assertNotNull(stale);
        assertNotNull(winner);
        winner.entity().jewelFree = 1;
        store.save(winner);

        PlayerContext first = store.loadById(created.id());
        assertNotNull(first);
        first.put("Cards", new ArrayList<>(List.of(Map.of("CardDatasId", 10104000))));
        stale.put("Cards", new ArrayList<>(List.of(Map.of("CardDatasId", 10104001))));

        WireError error = assertThrows(WireError.class, () -> store.saveAll(first, stale));
        assertEquals(409, error.status());
        assertTrue(store.loadById(created.id()).rowsSnapshot("Cards").isEmpty());
    }

    @Test
    void gachaPurchasePersistsBeginnerProgressBeforeTheRequestEnds() {
        String token = "gacha-progress-token";
        PlayerContext created = store.createGuest("Gacha Writer", token);
        created.entity().jewelFree = 5000;
        created.put("Cards", new ArrayList<>());
        created.put("Items", new ArrayList<>());
        store.save(created);

        gateway.dispatch("/v1/gacha/set_purchase",
                J.map("gacha_series_id", 1050316L, "gacha_set_purchase_type", 1),
                Map.of("authorization", "Bearer " + token,
                        "x-idempotency-key", "gacha-progress-save"));

        PlayerContext reloaded = store.loadById(created.id());
        assertNotNull(reloaded);
        Map<String, Object> mission = missionRow(
                reloaded.rowsSnapshot("beginner_missions"), 900201);
        assertEquals(1, J.intOr(mission.get("achieved_num"), 0));
        assertEquals(1L, jdbc.queryForObject(
                "SELECT COUNT(*) FROM idempotency_keys WHERE player_id = ? AND idem_key = ?",
                Long.class, created.id(), "gacha-progress-save"));
    }

    private static Map<String, Object> missionRow(List<Map<String, Object>> groups, int missionId) {
        for (Map<String, Object> group : groups) {
            for (Map<String, Object> row : J.rowsOr(group.get("mission_list"))) {
                if (J.intOr(row.get("mission_id"), -1) == missionId) {
                    return row;
                }
            }
        }
        throw new AssertionError("缺少 mission_id=" + missionId);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return (Map<String, Object>) value;
    }
}
