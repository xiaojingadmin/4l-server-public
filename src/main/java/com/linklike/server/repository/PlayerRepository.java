package com.linklike.server.repository;

import com.linklike.server.domain.Player;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PlayerRepository extends JpaRepository<Player, String> {

    Optional<Player> findByLoginAlias(String loginAlias);

    List<Player> findByDeviceSpecificId(String deviceSpecificId);

    List<Player> findByOfficialPlayerIdOrderByCreatedAsc(String officialPlayerId);

    boolean existsByPlayerId(String playerId);

    List<Player> findAllByOrderByCreatedAsc();
}
