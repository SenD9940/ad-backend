package com.orinan.db.aistudio.template;

import com.orinan.db.aistudio.enums.AiStudioKind;
import com.orinan.db.aistudio.category.AiStudioCategoryEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import java.util.Optional;
import java.util.List;

public interface AiTemplateRepository extends JpaRepository<AiTemplateEntity, Long> {
    @EntityGraph(attributePaths = "category")
    Optional<AiTemplateEntity> findByIdAndPublishedTrue(Long id);
    boolean existsByCategoryId(Long categoryId);
    @Query("select distinct c from AiTemplateEntity t join t.category c where t.published=true order by c.name, c.id")
    List<AiStudioCategoryEntity> findPublishedCategories();
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from AiTemplateEntity t where t.id=:id")
    Optional<AiTemplateEntity> findByIdForUpdate(@Param("id") Long id);
    @EntityGraph(attributePaths = "category")
    @Query("""
            select t from AiTemplateEntity t join t.category c where (:kind is null or t.kind=:kind)
              and (:categoryId is null or c.id=:categoryId)
              and (:q is null or lower(t.title) like :q escape '!' or lower(t.description) like :q escape '!'
                   or lower(c.name) like :q escape '!')
            """)
    Page<AiTemplateEntity> search(@Param("kind") AiStudioKind kind, @Param("categoryId") Long categoryId,
                                  @Param("q") String searchPattern, Pageable pageable);
    @EntityGraph(attributePaths = "category")
    @Query("""
            select t from AiTemplateEntity t join t.category c where t.published=true and (:kind is null or t.kind=:kind)
              and (:categoryId is null or c.id=:categoryId)
              and (:q is null or lower(t.title) like :q escape '!' or lower(t.description) like :q escape '!'
                   or lower(c.name) like :q escape '!')
            """)
    Page<AiTemplateEntity> searchPublished(@Param("kind") AiStudioKind kind, @Param("categoryId") Long categoryId,
                                           @Param("q") String searchPattern, Pageable pageable);
}
