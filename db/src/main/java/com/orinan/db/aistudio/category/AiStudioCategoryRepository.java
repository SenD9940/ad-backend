package com.orinan.db.aistudio.category;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import java.util.List;
import java.util.Optional;

public interface AiStudioCategoryRepository extends JpaRepository<AiStudioCategoryEntity, Long> {
    List<AiStudioCategoryEntity> findAllByOrderByNameAscIdAsc();
    boolean existsByNameIgnoreCase(String name);
    boolean existsByNameIgnoreCaseAndIdNot(String name, Long id);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from AiStudioCategoryEntity c where c.id=:id")
    Optional<AiStudioCategoryEntity> findByIdForUpdate(@Param("id") Long id);
}
