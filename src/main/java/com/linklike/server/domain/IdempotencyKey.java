package com.linklike.server.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.util.Objects;

/**
 * 幂等收据：商店 / 抽卡写入强制带 {@code x-idempotency-key}，同键重放返回原结果，
 * 同键不同请求体返回 409。
 */
@Entity
@Table(name = "idempotency_keys")
@IdClass(IdempotencyKey.Key.class)
public class IdempotencyKey {

    @Id
    @Column(name = "player_id")
    public String playerId;

    @Id
    @Column(name = "idem_key")
    public String idemKey;

    public String route;

    @Column(name = "request_hash", length = 64)
    public String requestHash;

    @Column(name = "response", columnDefinition = "LONGTEXT")
    public String response;

    public long createdAt;

    public IdempotencyKey() {
    }

    public IdempotencyKey(String playerId, String idemKey, String route, String requestHash,
                          String response, long createdAt) {
        this.playerId = playerId;
        this.idemKey = idemKey;
        this.route = route;
        this.requestHash = requestHash;
        this.response = response;
        this.createdAt = createdAt;
    }

    /** 复合主键。 */
    public static class Key implements Serializable {
        public String playerId;
        public String idemKey;

        public Key() {
        }

        public Key(String playerId, String idemKey) {
            this.playerId = playerId;
            this.idemKey = idemKey;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof Key key)) {
                return false;
            }
            return Objects.equals(playerId, key.playerId) && Objects.equals(idemKey, key.idemKey);
        }

        @Override
        public int hashCode() {
            return Objects.hash(playerId, idemKey);
        }
    }
}
