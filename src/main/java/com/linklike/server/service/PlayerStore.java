package com.linklike.server.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linklike.server.domain.Player;
import com.linklike.server.domain.PlayerState;
import com.linklike.server.domain.SessionRecord;
import com.linklike.server.protocol.WireError;
import com.linklike.server.repository.PlayerRepository;
import com.linklike.server.repository.PlayerStateRepository;
import com.linklike.server.repository.SessionRepository;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 玩家与会话的持久化门面，对应 Python 版 {@code server.py} 里的 {@code _users} /
 * {@code _sessions} 缓存加 {@code database.Database} 的组合。
 *
 * <p>没有内存缓存：每次请求按需从数据库装载一个 {@link PlayerContext}，请求结束时
 * 显式 {@link #save(PlayerContext)}。这样多账号之间的写入天然隔离，不存在缓存不一致。
 */
@Service
public class PlayerStore {

    /** 官方 Link!Like ID 是 9 位大写字母数字（玩家 ID 同时也是好友搜索键）。 */
    private static final String PLAYER_ID_ALPHABET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ";
    private static final int PLAYER_ID_LENGTH = 9;

    private static final SecureRandom RANDOM = new SecureRandom();

    private final PlayerRepository players;
    private final SessionRepository sessions;
    private final PlayerStateRepository states;
    private final ObjectMapper mapper;

    public PlayerStore(PlayerRepository players, SessionRepository sessions,
                       PlayerStateRepository states, ObjectMapper mapper) {
        this.players = players;
        this.sessions = sessions;
        this.states = states;
        this.mapper = mapper;
    }

    // ------------------------------------------------------------------
    // 令牌与 ID
    // ------------------------------------------------------------------

    public String genToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    public String newPlayerId() {
        for (int attempt = 0; attempt < 1000; attempt++) {
            StringBuilder sb = new StringBuilder(PLAYER_ID_LENGTH);
            for (int i = 0; i < PLAYER_ID_LENGTH; i++) {
                sb.append(PLAYER_ID_ALPHABET.charAt(RANDOM.nextInt(PLAYER_ID_ALPHABET.length())));
            }
            String candidate = sb.toString();
            if (!players.existsByPlayerId(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("无法生成未占用的玩家 ID");
    }

    // ------------------------------------------------------------------
    // 装载与保存
    // ------------------------------------------------------------------

    /** 主键，或把 pid 当作 LoginAlias 命中的账号（短 ID 迁移后的兼容）。 */
    @Transactional(readOnly = true)
    public String resolvePlayerId(String pid) {
        if (pid == null || pid.isEmpty()) {
            return null;
        }
        if (players.existsByPlayerId(pid)) {
            return pid;
        }
        return players.findByLoginAlias(pid).map(p -> p.playerId).orElse(null);
    }

    @Transactional(readOnly = true)
    public Player findEntity(String pid) {
        String resolved = resolvePlayerId(pid);
        return resolved == null ? null : players.findById(resolved).orElse(null);
    }

    @Transactional(readOnly = true)
    public PlayerContext loadById(String pid) {
        String resolved = resolvePlayerId(pid);
        if (resolved == null) {
            return null;
        }
        Player entity = players.findById(resolved).orElse(null);
        return entity == null ? null : new PlayerContext(entity, loadState(resolved));
    }

    /** 按 Bearer 会话令牌装载玩家。 */
    @Transactional(readOnly = true)
    public PlayerContext loadByToken(String token) {
        if (token == null || token.isEmpty()) {
            return null;
        }
        String pid = playerIdForToken(token);
        return pid == null ? null : loadById(pid);
    }

    /** 按设备标识找账号（重装后缓存里的 PlayerId 失效时用）。 */
    @Transactional(readOnly = true)
    public PlayerContext loadByDevice(String device) {
        if (device == null || device.isEmpty()) {
            return null;
        }
        List<Player> found = players.findByDeviceSpecificId(device);
        return found.isEmpty() ? null : loadById(found.get(0).playerId);
    }

    /** 创建访客账号并绑定令牌；令牌为空时新生成。 */
    @Transactional
    public PlayerContext createGuest(String name, String token) {
        Player entity = new Player();
        entity.playerId = newPlayerId();
        entity.deviceSpecificId = UUID.randomUUID().toString();
        entity.playerName = (name == null || name.isBlank()) ? "Player" : name.trim();
        entity.playerLevel = 1;
        entity.platformType = "Guest";
        entity.sessionToken = (token == null || token.isEmpty()) ? genToken() : token;
        entity.created = Instant.now().getEpochSecond();
        players.save(entity);
        PlayerContext context = new PlayerContext(entity, new LinkedHashMap<>());
        setSession(entity.sessionToken, entity.playerId);
        return context;
    }

    /**
     * 落库：玩家标量字段 + 本次装载过的全部状态键。
     *
     * <p>状态按「已装载即写回」处理，避免调用方修改了 {@link PlayerContext#rows(String)}
     * 返回的可变集合却忘记标脏而丢数据。
     */
    @Transactional
    public void save(PlayerContext context) {
        players.save(context.entity());
        for (String key : context.loadedKeys()) {
            Object value = context.rawState().get(key);
            if (value == null) {
                states.deleteOne(context.id(), key);
            } else {
                states.save(new PlayerState(context.id(), key, serialize(value)));
            }
        }
    }

    @Transactional
    public void saveEntity(Player entity) {
        players.save(entity);
    }

    @Transactional
    public void deletePlayer(String pid) {
        String resolved = resolvePlayerId(pid);
        if (resolved == null) {
            return;
        }
        sessions.deleteByPlayerId(resolved);
        states.deleteByPlayerId(resolved);
        players.deleteById(resolved);
    }

    @Transactional(readOnly = true)
    public List<Player> listPlayers() {
        return players.findAllByOrderByCreatedAsc();
    }

    // ------------------------------------------------------------------
    // 会话
    // ------------------------------------------------------------------

    @Transactional(readOnly = true)
    public String playerIdForToken(String token) {
        if (token == null || token.isEmpty()) {
            return null;
        }
        return sessions.findById(token).map(row -> row.playerId).orElse(null);
    }

    /** 把令牌关联到玩家（幂等）。 */
    @Transactional
    public void setSession(String token, String playerId) {
        if (token == null || token.isEmpty() || playerId == null || playerId.isEmpty()) {
            throw new IllegalArgumentException("会话令牌与玩家 ID 都不能为空");
        }
        if (!players.existsByPlayerId(playerId)) {
            throw new IllegalArgumentException("会话归属的玩家不存在: " + playerId);
        }
        sessions.save(new SessionRecord(token, playerId, Instant.now().getEpochSecond()));
    }

    // ------------------------------------------------------------------
    // JSON 状态
    // ------------------------------------------------------------------

    private Map<String, Object> loadState(String playerId) {
        Map<String, Object> state = new LinkedHashMap<>();
        for (PlayerState row : states.findByPlayerId(playerId)) {
            state.put(row.stateKey, parse(row.payload));
        }
        return state;
    }

    private Object parse(String payload) {
        try {
            return mapper.readValue(payload, Object.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("玩家状态 JSON 损坏，无法解析", e);
        }
    }

    private String serialize(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw WireError.badRequest("状态无法序列化: " + e.getOriginalMessage());
        }
    }

    /** 供需要读任意目录/状态 JSON 的模块复用。 */
    public ObjectMapper mapper() {
        return mapper;
    }

    public Optional<Player> findOptional(String pid) {
        String resolved = resolvePlayerId(pid);
        return resolved == null ? Optional.empty() : players.findById(resolved);
    }
}
