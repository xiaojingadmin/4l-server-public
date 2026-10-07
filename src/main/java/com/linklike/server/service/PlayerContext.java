package com.linklike.server.service;

import com.linklike.server.domain.Player;
import com.linklike.server.protocol.J;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 一次请求内的玩家状态门面，对应 Python 版业务代码里直接操作 {@code player} dict 的写法。
 *
 * <p>玩家标量字段在 {@link #entity()}；集合型状态（Cards、Decks、PresentBox…）和低频标量
 * 通过 {@link #rows(String)} / {@link #node(String)} 读写。这些方法返回的是内部结构的
 * <b>可变引用</b>，与 Python 语义一致：改完调用 {@link PlayerStore#save(PlayerContext)} 落库。
 */
public class PlayerContext {

    private final Player entity;
    private final Map<String, Object> state;
    private final Set<String> loadedKeys;

    PlayerContext(Player entity, Map<String, Object> state) {
        this.entity = entity;
        this.state = state;
        this.loadedKeys = new HashSet<>(state.keySet());
    }

    /** 玩家标量实体；赋值后由 {@link PlayerStore#save(PlayerContext)} 落库。 */
    public Player entity() {
        return entity;
    }

    public String id() {
        return entity.playerId;
    }

    public String name() {
        String name = entity.playerName;
        return name == null || name.isEmpty() ? "Player" : name;
    }

    /** 请求期间访问过的状态键（含新建的空集合）。 */
    Set<String> loadedKeys() {
        return loadedKeys;
    }

    Map<String, Object> rawState() {
        return state;
    }

    // ------------------------------------------------------------------
    // 读取
    // ------------------------------------------------------------------

    public boolean has(String key) {
        return state.containsKey(key);
    }

    public Object raw(String key) {
        return state.get(key);
    }

    /**
     * 列表型集合。缺失时创建空列表并挂到状态里，返回的就是内部引用，
     * 因此 {@code ctx.rows("Cards").add(card)} 直接生效。
     */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> rows(String key) {
        Object value = state.get(key);
        if (value instanceof List<?> list) {
            return (List<Map<String, Object>>) (List<?>) list;
        }
        if (value != null) {
            return new ArrayList<>();
        }
        List<Map<String, Object>> created = new ArrayList<>();
        state.put(key, created);
        loadedKeys.add(key);
        return created;
    }

    /**
     * 只读语义的列表副本，<b>不会</b>创建缺失的键（对应 Python 的 {@code player.get(key, [])}）。
     *
     * <p>纯读取路径（首页、资料）用这个方法，避免只因为「读过一遍」就在账号状态里留下
     * 一堆空集合行。
     */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> rowsSnapshot(String key) {
        Object value = state.get(key);
        if (!(value instanceof List<?> list)) {
            return new ArrayList<>();
        }
        List<Map<String, Object>> copy = new ArrayList<>(list.size());
        for (Object item : list) {
            if (item instanceof Map<?, ?> map) {
                copy.add((Map<String, Object>) map);
            }
        }
        return copy;
    }

    /**
     * 纯标量列表型状态（{@code TutorialSteps}、{@code FinishedTutorials} 这类整型列表）。
     *
     * <p>与 {@link #rows(String)} 一样返回内部引用，缺失时创建空表并挂到状态里。
     * 不能对同一个键混用这两个方法：Python 里这些键是 {@code [1, 2]}，用行语义读会出错。
     */
    @SuppressWarnings("unchecked")
    public List<Object> scalarList(String key) {
        Object value = state.get(key);
        if (value instanceof List<?> list) {
            return (List<Object>) list;
        }
        List<Object> created = new ArrayList<>();
        state.put(key, created);
        loadedKeys.add(key);
        return created;
    }

    /** 只读语义的标量列表副本，不会创建缺失的键（对应 Python 的 {@code player.get(k, [])}）。 */
    public List<Object> scalarsOrEmpty(String key) {
        Object value = state.get(key);
        return value instanceof List<?> list ? new ArrayList<>(list) : new ArrayList<>();
    }

    /** 对象型状态；缺失返回 null。 */
    @SuppressWarnings("unchecked")
    public Map<String, Object> node(String key) {
        Object value = state.get(key);
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : null;
    }

    /** 对象型状态；缺失返回可写的空对象并挂到状态里。 */
    @SuppressWarnings("unchecked")
    public Map<String, Object> nodeOrCreate(String key) {
        Object value = state.get(key);
        if (value instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        Map<String, Object> created = new LinkedHashMap<>();
        state.put(key, created);
        loadedKeys.add(key);
        return created;
    }

    public Map<String, Object> nodeOrEmpty(String key) {
        Map<String, Object> node = node(key);
        return node == null ? new LinkedHashMap<>() : node;
    }

    // ------------------------------------------------------------------
    // 写入
    // ------------------------------------------------------------------

    public void put(String key, Object value) {
        state.put(key, value);
        loadedKeys.add(key);
    }

    public void remove(String key) {
        state.remove(key);
        loadedKeys.add(key);
    }

    // ------------------------------------------------------------------
    // 标量状态便捷读（键不存在时返回兜底值）
    // ------------------------------------------------------------------

    public String str(String key) {
        return J.str(state.get(key));
    }

    public String str(String key, String fallback) {
        String value = J.nonEmpty(state.get(key));
        return value == null ? fallback : value;
    }

    public int intOf(String key, int fallback) {
        return J.intOr(state.get(key), fallback);
    }

    public long longOf(String key, long fallback) {
        return J.longOr(state.get(key), fallback);
    }

    public boolean boolOf(String key, boolean fallback) {
        return J.boolOr(state.get(key), fallback);
    }

    /**
     * Python 的 {@code player.get(key)} 布尔判定（缺失、0、空串、空集合都是 false）。
     *
     * <p>与 {@link #rows(String)} 不同，这个方法<b>不会</b>创建缺失的键，因此
     * 纯读取路径（首页、资料）不会在账号里留下凭空多出来的空集合行。
     */
    public boolean truthy(String key) {
        return J.truthy(state.get(key));
    }

    /** 在列表型集合里按某个属性找一行，对应 Python 的 {@code next((x for x in rows if ...), None)}。 */
    public Map<String, Object> findRow(String key, String field, Object value) {
        for (Map<String, Object> row : rows(key)) {
            if (value == null ? row.get(field) == null : value.equals(row.get(field))) {
                return row;
            }
        }
        return null;
    }
}
