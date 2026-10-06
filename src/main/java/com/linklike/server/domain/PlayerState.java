package com.linklike.server.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.util.Objects;

/**
 * 玩家状态的一行：{@code (player_id, state_key) -> JSON payload}。
 *
 * <p>标量扩展（如 LauncherInfo、各类设置）存单值 JSON；集合型状态（Cards、Decks、
 * PresentBox…）存整个 JSON 数组。这样协议演进时不需要改表结构。
 */
@Entity
@Table(name = "player_state")
@IdClass(PlayerState.Key.class)
public class PlayerState {

    @Id
    @Column(name = "player_id")
    public String playerId;

    @Id
    @Column(name = "state_key")
    public String stateKey;

    @Column(name = "payload", columnDefinition = "LONGTEXT")
    public String payload;

    public PlayerState() {
    }

    public PlayerState(String playerId, String stateKey, String payload) {
        this.playerId = playerId;
        this.stateKey = stateKey;
        this.payload = payload;
    }

    /** 复合主键。 */
    public static class Key implements Serializable {
        public String playerId;
        public String stateKey;

        public Key() {
        }

        public Key(String playerId, String stateKey) {
            this.playerId = playerId;
            this.stateKey = stateKey;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof Key key)) {
                return false;
            }
            return Objects.equals(playerId, key.playerId) && Objects.equals(stateKey, key.stateKey);
        }

        @Override
        public int hashCode() {
            return Objects.hash(playerId, stateKey);
        }
    }
}
