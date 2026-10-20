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
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
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
    private final JdbcTemplate jdbc;
    private final EntityManager entities;
    private final ObjectMapper mapper;

    public PlayerStore(PlayerRepository players, SessionRepository sessions,
                       PlayerStateRepository states, JdbcTemplate jdbc,
                       EntityManager entities, ObjectMapper mapper) {
        this.players = players;
        this.sessions = sessions;
        this.states = states;
        this.jdbc = jdbc;
        this.entities = entities;
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
        List<Player> found = players.findByDeviceSpecificIdOrderByCreatedAsc(device);
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
     * 落库：玩家标量字段 + 本次请求实际改动的状态键。
     *
     * <p>装载时保存一份状态快照；调用方通过 {@link PlayerContext#rows(String)} 等可变引用
     * 修改内容后，保存阶段比较快照找出真正变化的键。这样既不会忘记标脏，也不会把未变化的
     * 大 JSON 行重复写回。
     */
    @Transactional
    public void save(PlayerContext context) {
        saveState(context);
    }

    /** 同一事务内保存多个账号；用于关注、好友等一次请求修改两个玩家的接口。 */
    @Transactional
    public void saveAll(PlayerContext... contexts) {
        Map<String, PlayerContext> unique = new LinkedHashMap<>();
        for (PlayerContext context : contexts) {
            unique.put(context.id(), context);
        }
        // 固定按玩家 ID 顺序加锁，两个互相关注/加好友的请求不会形成反向锁序。
        for (String playerId : unique.keySet().stream().sorted().toList()) {
            saveState(unique.get(playerId));
        }
    }

    /**
     * 幂等键与受保护状态在同一事务内提交。
     *
     * <p>直接调用 handler 时没有 {@code WireGateway} 的外层事务，单独写 key 再 save 会把
     * “已占键但状态未落库”永久留下；因此生产调用点统一改用这个方法。
     */
    @Transactional
    public void saveWithIdempotency(PlayerContext context, String key, String route,
                                    String fingerprint, Object response) {
        insertIdempotency(context.id(), key, route, fingerprint, response);
        saveState(context);
    }

    private void saveState(PlayerContext context) {
        try {
            Player managed = players.save(context.entity());
            Set<String> changed = context.changedKeys();
            // state-only 变更不会让 Hibernate 自动 UPDATE players；有变化时才强制递增版本。
            if (!changed.isEmpty()) {
                entities.lock(managed, LockModeType.OPTIMISTIC_FORCE_INCREMENT);
            }
            for (String key : changed) {
                Object value = context.rawState().get(key);
                if (value == null) {
                    states.deleteOne(context.id(), key);
                } else {
                    states.save(new PlayerState(context.id(), key, serialize(value)));
                }
            }
            players.flush();
            context.entity().version = managed.version;
            context.markSaved();
        } catch (OptimisticLockingFailureException e) {
            throw WireError.conflict(
                    "Player state changed concurrently; retry the request");
        }
    }

    /**
     * 在独立表中登记幂等键，供并发请求做数据库级唯一性判定。
     *
     * <p>收据 JSON 仍用于重放响应；这张表只负责让两个同时到达的同键请求在唯一约束上
     * 分出胜负，避免它们都在各自快照里“没查到回执”后重复结算。
     */
    private void insertIdempotency(String playerId, String key, String route,
                                   String fingerprint, Object response) {
        try {
            jdbc.update("INSERT INTO idempotency_keys "
                            + "(player_id, idem_key, route, request_hash, response, created_at) "
                            + "VALUES (?, ?, ?, ?, ?, ?)",
                    playerId, key, route, fingerprint, serialize(response),
                    Instant.now().getEpochSecond());
        } catch (DataIntegrityViolationException e) {
            throw WireError.conflict("Idempotency key already used");
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

    /**
     * 签发会话：把令牌写到玩家上并落库，再建立令牌映射 —— 对应 Python 版
     * {@code server.set_session}。
     *
     * <p>与 {@link #setSession(String, String)} 的区别是这里会先写
     * {@code player.SessionToken}：客户端登录后读到的是最新令牌，登录响应里的
     * {@code SessionToken} 必须与此一致。
     *
     * @param token 指定令牌；为空时新生成
     * @return 实际写入的令牌
     */
    @Transactional
    public String issueSession(PlayerContext context, String token) {
        String issued = (token == null || token.isEmpty()) ? genToken() : token;
        if (!players.existsByPlayerId(context.id())) {
            throw new IllegalArgumentException("会话归属的玩家不存在: " + context.id());
        }
        context.entity().sessionToken = issued;
        save(context);
        sessions.save(new SessionRecord(issued, context.id(), Instant.now().getEpochSecond()));
        return issued;
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
