package com.linklike.server.repository;

import com.linklike.server.domain.PlayerState;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PlayerStateRepository extends JpaRepository<PlayerState, PlayerState.Key> {

    List<PlayerState> findByPlayerId(String playerId);

    @Modifying
    @Query("delete from PlayerState s where s.playerId = :playerId and s.stateKey = :key")
    void deleteOne(@Param("playerId") String playerId, @Param("key") String key);

    @Modifying
    @Query("delete from PlayerState s where s.playerId = :playerId")
    void deleteByPlayerId(@Param("playerId") String playerId);
}
