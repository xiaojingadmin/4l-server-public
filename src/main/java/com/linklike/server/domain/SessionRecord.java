package com.linklike.server.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** 会话令牌到玩家 ID 的映射。 */
@Entity
@Table(name = "sessions")
public class SessionRecord {

    @Id
    @Column(name = "session_token")
    public String sessionToken;

    @Column(name = "player_id")
    public String playerId;

    public long issuedAt;

    public SessionRecord() {
    }

    public SessionRecord(String sessionToken, String playerId, long issuedAt) {
        this.sessionToken = sessionToken;
        this.playerId = playerId;
        this.issuedAt = issuedAt;
    }
}
