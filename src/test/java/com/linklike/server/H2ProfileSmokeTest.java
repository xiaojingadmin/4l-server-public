package com.linklike.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.linklike.server.wire.WireGrants;
import com.linklike.server.wire.WireHandler;
import com.linklike.server.wire.WireRequest;
import com.linklike.server.wire.WireRouter;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
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
    void getTermsHandlerIsImplemented() {
        WireHandler handler = router.resolve("/v1/register/getterms");
        assertNotNull(handler);
        Map<String, Object> out = handler.handle(new WireRequest(
                "/v1/register/getterms", Map.of(), Map.of(), null, null, mapper, config));
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
}
