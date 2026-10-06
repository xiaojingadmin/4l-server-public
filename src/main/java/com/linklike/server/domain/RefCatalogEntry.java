package com.linklike.server.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.util.Objects;

/**
 * 参考目录 / master 数据的一行：{@code (catalog_key, entity_id) -> JSON payload}。
 *
 * <p>{@code catalog_key} 对应 Python 版的 {@code ref_<type>} 表名（如 {@code shops}、
 * {@code gacha_series}），{@code position} 保持导入顺序。
 */
@Entity
@Table(name = "ref_catalogs")
@IdClass(RefCatalogEntry.Key.class)
public class RefCatalogEntry {

    @Id
    @Column(name = "catalog_key")
    public String catalogKey;

    @Id
    @Column(name = "entity_id")
    public String entityId;

    public int position;

    @Column(name = "payload", columnDefinition = "LONGTEXT")
    public String payload;

    public RefCatalogEntry() {
    }

    public RefCatalogEntry(String catalogKey, String entityId, int position, String payload) {
        this.catalogKey = catalogKey;
        this.entityId = entityId;
        this.position = position;
        this.payload = payload;
    }

    /** 复合主键。 */
    public static class Key implements Serializable {
        public String catalogKey;
        public String entityId;

        public Key() {
        }

        public Key(String catalogKey, String entityId) {
            this.catalogKey = catalogKey;
            this.entityId = entityId;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof Key key)) {
                return false;
            }
            return Objects.equals(catalogKey, key.catalogKey) && Objects.equals(entityId, key.entityId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(catalogKey, entityId);
        }
    }
}
