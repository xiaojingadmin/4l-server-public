package com.linklike.server.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linklike.server.domain.RefCatalogEntry;
import com.linklike.server.repository.RefCatalogRepository;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 参考目录（master / 客户端静态数据）的读写门面，对应 Python 版
 * {@code database.Database} 的 {@code get_reference} / {@code list_reference} /
 * {@code put_reference}。
 *
 * <p>Python 版每种目录一张 {@code ref_<type>} 表，字段按记录模型展开成列；Java 版统一落在
 * {@code ref_catalogs}（{@code catalog_key + entity_id -> JSON}），所以这里返回的记录是
 * 解析后的 {@code Map}，调用方按 PascalCase / snake_case 键名取用，与 Python 里的
 * {@code dict} 用法一一对应。
 *
 * <p>{@code catalog_key} 沿用 Python 的表名去掉 {@code ref_} 前缀：{@code profile_defaults}、
 * {@code characters}、{@code initial_cards}、{@code quest_stages}……
 */
@Service
public class RefCatalogStore {

    private final RefCatalogRepository repository;
    private final ObjectMapper mapper;

    public RefCatalogStore(RefCatalogRepository repository, ObjectMapper mapper) {
        this.repository = repository;
        this.mapper = mapper;
    }

    // ------------------------------------------------------------------
    // 读
    // ------------------------------------------------------------------

    /** 取一条记录；不存在返回 null（对应 Python 的 {@code get_reference(..., None)}）。 */
    @Transactional(readOnly = true)
    public Map<String, Object> get(String catalogKey, Object entityId) {
        if (entityId == null) {
            return null;
        }
        return repository.findByCatalogKeyAndEntityId(catalogKey, String.valueOf(entityId))
                .map(row -> asMap(parse(row.payload)))
                .orElse(null);
    }

    /** 取列表型记录（只要 {@code payload} 是数组就返回它的行，否则返回空表）。 */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> list(String catalogKey) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (RefCatalogEntry row : repository.findByCatalogKeyOrderByPositionAsc(catalogKey)) {
            Object value = parse(row.payload);
            if (value instanceof List<?> list) {
                for (Object item : list) {
                    if (item instanceof Map<?, ?> map) {
                        rows.add(asMap(map));
                    }
                }
            } else if (value instanceof Map<?, ?> map) {
                rows.add(asMap(map));
            }
        }
        return rows;
    }

    /** 取一条记录的原始 JSON 形态（可能是数组，用于「一条记录里存整张表」的场景）。 */
    @Transactional(readOnly = true)
    public Object raw(String catalogKey, Object entityId) {
        if (entityId == null) {
            return null;
        }
        return repository.findByCatalogKeyAndEntityId(catalogKey, String.valueOf(entityId))
                .map(row -> parse(row.payload))
                .orElse(null);
    }

    @Transactional(readOnly = true)
    public boolean has(String catalogKey, Object entityId) {
        return entityId != null
                && repository.findByCatalogKeyAndEntityId(catalogKey, String.valueOf(entityId))
                        .isPresent();
    }

    @Transactional(readOnly = true)
    public long count(String catalogKey) {
        return repository.countByCatalogKey(catalogKey);
    }

    // ------------------------------------------------------------------
    // 写
    // ------------------------------------------------------------------

    /**
     * 写入（或覆盖）一条记录，对应 Python 的 {@code put_reference}。
     *
     * <p>{@code position} 的处理与 Python 一致：已存在则沿用原位置，新记录排在最后，
     * 这样 {@code list} 的顺序不随重复导入改变。
     */
    @Transactional
    public void put(String catalogKey, Object entityId, Object payload) {
        put(catalogKey, entityId, payload, false);
    }

    @Transactional
    public void put(String catalogKey, Object entityId, Object payload, boolean onlyIfMissing) {
        String key = String.valueOf(entityId);
        RefCatalogEntry existing = repository
                .findByCatalogKeyAndEntityId(catalogKey, key)
                .orElse(null);
        if (existing != null && onlyIfMissing) {
            return;
        }
        int position = existing != null
                ? existing.position
                : (int) repository.countByCatalogKey(catalogKey);
        repository.save(new RefCatalogEntry(catalogKey, key, position, serialize(payload)));
    }

    /** 整张表一次写入（{@code position} 用行序号）。表非空时直接跳过，对应 Python 版
     * {@code wire_catalog._seed_rows} 开头的 {@code if db.list_reference(etype): return}。 */
    @Transactional
    public void seedBatch(String catalogKey, List<Map<String, Object>> rows,
                          java.util.function.Function<Map<String, Object>, Object> identity) {
        if (rows.isEmpty() || repository.countByCatalogKey(catalogKey) > 0) {
            return;
        }
        List<RefCatalogEntry> entries = new ArrayList<>(rows.size());
        int position = 0;
        for (Map<String, Object> row : rows) {
            entries.add(new RefCatalogEntry(catalogKey, String.valueOf(identity.apply(row)),
                    position++, serialize(row)));
        }
        repository.saveAll(entries);
    }

    // ------------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------------

    private Object parse(String payload) {
        try {
            return mapper.readValue(payload, Object.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("参考目录 JSON 损坏，无法解析", e);
        }
    }

    private String serialize(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("参考目录无法序列化", e);
        }
    }

    /**
     * 把 JSON 解析结果当作对象集合使用；不是对象则返回 null，调用方据此走 501 分支，
     * 而不是拿空对象糊过去。
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        if (value instanceof Map<?, ?> map) {
            return new LinkedHashMap<>((Map<String, Object>) map);
        }
        return null;
    }
}
