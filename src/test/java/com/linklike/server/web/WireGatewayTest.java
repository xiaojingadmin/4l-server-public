package com.linklike.server.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.linklike.server.config.ServerProperties;
import com.linklike.server.protocol.WireError;
import com.linklike.server.service.PlayerContext;
import com.linklike.server.service.PlayerStore;
import com.linklike.server.wire.RouteSnapshot;
import com.linklike.server.wire.WireModule;
import com.linklike.server.wire.WireRouter;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** {@link WireGateway} 的分发顺序：先判路径（501），再校验 API key（401）。 */
class WireGatewayTest {

    private ObjectMapper mapper;
    private WireRouter router;
    private PlayerStore store;

    @BeforeEach
    void setUp() {
        mapper = new ObjectMapper();
        WireModule module = new WireModule() {
            @Override
            public void register(WireRouter router) {
                router.register("/v1/test/echo", request -> Map.of("ok", true));
            }
        };
        router = new WireRouter(List.of(module), new RouteSnapshot(mapper));
        store = mock(PlayerStore.class);
    }

    @Test
    void unknownPathIs501EvenWithAWrongApiKey() {
        WireGateway gateway = new WireGateway(router, store, mapper, config("secret"));

        WireError error = assertThrows(WireError.class, () -> gateway.dispatch(
                "/v1/test/unknown", Map.of(), Map.of("x-api-key", "wrong")));

        assertEquals(501, error.status());
    }

    @Test
    void wrongApiKeyIs401() {
        WireGateway gateway = new WireGateway(router, store, mapper, config("secret"));

        WireError error = assertThrows(WireError.class, () -> gateway.dispatch(
                "/v1/test/echo", Map.of(), Map.of("x-api-key", "wrong")));

        assertEquals(401, error.status());
    }

    @Test
    void missingApiKeyIs401WhenOneIsConfigured() {
        WireGateway gateway = new WireGateway(router, store, mapper, config("secret"));

        assertEquals(401, assertThrows(WireError.class,
                () -> gateway.dispatch("/v1/test/echo", Map.of(), Map.of())).status());
    }

    @Test
    void matchingApiKeyRunsTheHandler() {
        WireGateway gateway = new WireGateway(router, store, mapper, config("secret"));

        WireGateway.Result result = gateway.dispatch(
                "/v1/test/echo", Map.of(), Map.of("x-api-key", "secret"));

        assertEquals(Map.of("ok", true), result.body());
        assertNull(result.player());
    }

    @Test
    void apiKeyCheckIsSkippedWhenOff() {
        WireGateway gateway = new WireGateway(router, store, mapper, config("off"));

        assertNotNull(gateway.dispatch("/v1/test/echo", Map.of(), Map.of()).body());
    }

    @Test
    void aNullBodyBecomesAnEmptyObject() {
        WireGateway gateway = new WireGateway(router, store, mapper, config("off"));
        router.register("/v1/test/null", request -> null);

        WireGateway.Result result = gateway.dispatch("/v1/test/null", Map.of(), Map.of());

        assertEquals(Map.of(), result.body());
    }

    @Test
    void trailingSlashAndCaseAreNormalizedBeforeDispatch() {
        WireGateway gateway = new WireGateway(router, store, mapper, config("off"));

        assertEquals(Map.of("ok", true),
                gateway.dispatch("/V1/Test/Echo/", Map.of(), Map.of()).body());
    }

    @Test
    void explicitUnimplementedHandlerStillYields501() {
        WireGateway gateway = new WireGateway(router, store, mapper, config("off"));
        router.registerUnimplemented(List.of("/v1/test/write"));

        assertEquals(501, assertThrows(WireError.class,
                () -> gateway.dispatch("/v1/test/write", Map.of(), Map.of())).status());
    }

    @Test
    void bearerTokenParsing() {
        assertEquals("abc", WireGateway.bearerToken("Bearer abc"));
        assertEquals("abc", WireGateway.bearerToken("bearer   abc "));
        assertNull(WireGateway.bearerToken("Basic abc"));
        assertNull(WireGateway.bearerToken("Bearer "));
        assertNull(WireGateway.bearerToken(null));
    }

    @Test
    void tokenIsResolvedIntoAPlayer() {
        PlayerContext player = mock(PlayerContext.class);
        when(store.loadByToken(any())).thenReturn(player);
        WireGateway gateway = new WireGateway(router, store, mapper, config("off"));

        WireGateway.Result result = gateway.dispatch("/v1/test/echo", Map.of(),
                Map.of("authorization", "Bearer token"));

        assertEquals(player, result.player());
    }

    private static ServerProperties config(String apiKey) {
        ServerProperties config = new ServerProperties();
        config.setApiKey(apiKey);
        return config;
    }
}
