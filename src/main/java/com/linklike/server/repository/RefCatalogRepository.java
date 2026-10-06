package com.linklike.server.repository;

import com.linklike.server.domain.RefCatalogEntry;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RefCatalogRepository extends JpaRepository<RefCatalogEntry, RefCatalogEntry.Key> {

    List<RefCatalogEntry> findByCatalogKeyOrderByPositionAsc(String catalogKey);

    Optional<RefCatalogEntry> findByCatalogKeyAndEntityId(String catalogKey, String entityId);

    long countByCatalogKey(String catalogKey);
}
