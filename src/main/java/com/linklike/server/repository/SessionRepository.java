package com.linklike.server.repository;

import com.linklike.server.domain.SessionRecord;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SessionRepository extends JpaRepository<SessionRecord, String> {

    List<SessionRecord> findByPlayerId(String playerId);

    @Modifying
    @Query("delete from SessionRecord s where s.playerId = :playerId")
    void deleteByPlayerId(@Param("playerId") String playerId);
}
