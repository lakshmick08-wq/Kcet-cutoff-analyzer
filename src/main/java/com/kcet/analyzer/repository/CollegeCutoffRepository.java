package com.kcet.analyzer.repository;

import com.kcet.analyzer.model.CollegeCutoff;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * CollegeCutoffRepository — Spring Data JPA Repository
 *
 * All queries are now category-aware: every paginated/stats/export query
 * accepts a category parameter (e.g. "GM", "2AG", "SCG") and filters records
 * to that single category.
 *
 * The distinct-categories query lets the frontend build a dynamic dropdown
 * with no hardcoded values.
 */
@Repository
public interface CollegeCutoffRepository extends JpaRepository<CollegeCutoff, Long> {

    // ─── Categories ──────────────────────────────────────────────────────────

    /**
     * All distinct category codes present for a session.
     * Sorted in application layer (GM first, then alphabetical).
     */
    @Query("SELECT DISTINCT c.category FROM CollegeCutoff c WHERE c.sessionId = :sessionId")
    List<String> findDistinctCategoriesBySessionId(@Param("sessionId") String sessionId);

    // ─── Core paginated query ────────────────────────────────────────────────

    /**
     * Paginated, filtered, sorted results for a specific session + category.
     *
     * maxRank = 0 means "no rank filter" (show all colleges).
     * When maxRank > 0, only records where cutoffRank <= maxRank are returned
     * (i.e., colleges the user can potentially get into).
     */
    @Query("""
        SELECT c FROM CollegeCutoff c
        WHERE c.sessionId = :sessionId
          AND c.category  = :category
          AND (:maxRank = 0 OR c.cutoffRank <= :maxRank)
          AND (:search IS NULL OR :search = ''
               OR LOWER(c.collegeName) LIKE LOWER(CONCAT('%', :search, '%'))
               OR LOWER(c.branchName)  LIKE LOWER(CONCAT('%', :search, '%')))
          AND (:branch IS NULL OR :branch = ''
               OR LOWER(c.branchName)  LIKE LOWER(CONCAT('%', :branch, '%')))
        """)
    Page<CollegeCutoff> findByFilters(
        @Param("sessionId") String   sessionId,
        @Param("category")  String   category,
        @Param("maxRank")   int      maxRank,
        @Param("search")    String   search,
        @Param("branch")    String   branch,
        Pageable pageable
    );

    // ─── Export: all matching records, no pagination ─────────────────────────

    @Query("""
        SELECT c FROM CollegeCutoff c
        WHERE c.sessionId = :sessionId
          AND c.category  = :category
          AND (:maxRank = 0 OR c.cutoffRank <= :maxRank)
          AND (:search IS NULL OR :search = ''
               OR LOWER(c.collegeName) LIKE LOWER(CONCAT('%', :search, '%'))
               OR LOWER(c.branchName)  LIKE LOWER(CONCAT('%', :search, '%')))
          AND (:branch IS NULL OR :branch = ''
               OR LOWER(c.branchName)  LIKE LOWER(CONCAT('%', :branch, '%')))
        ORDER BY c.cutoffRank ASC
        """)
    List<CollegeCutoff> findAllByFiltersForExport(
        @Param("sessionId") String   sessionId,
        @Param("category")  String   category,
        @Param("maxRank")   int      maxRank,
        @Param("search")    String   search,
        @Param("branch")    String   branch
    );

    // ─── Statistics ──────────────────────────────────────────────────────────

    /** Total records for a given session + category */
    long countBySessionIdAndCategory(String sessionId, String category);

    /** Count records where branch name contains a keyword (category-scoped) */
    @Query("""
        SELECT COUNT(c) FROM CollegeCutoff c
        WHERE c.sessionId = :sid
          AND c.category  = :cat
          AND LOWER(c.branchName) LIKE LOWER(CONCAT('%', :keyword, '%'))
        """)
    long countBySessionCategoryAndBranch(
        @Param("sid")     String sessionId,
        @Param("cat")     String category,
        @Param("keyword") String keyword
    );

    @Query("SELECT MIN(c.cutoffRank) FROM CollegeCutoff c WHERE c.sessionId = :sid AND c.category = :cat")
    Integer findMinRank(@Param("sid") String sid, @Param("cat") String cat);

    @Query("SELECT MAX(c.cutoffRank) FROM CollegeCutoff c WHERE c.sessionId = :sid AND c.category = :cat")
    Integer findMaxRank(@Param("sid") String sid, @Param("cat") String cat);

    @Query("SELECT AVG(c.cutoffRank) FROM CollegeCutoff c WHERE c.sessionId = :sid AND c.category = :cat")
    Double findAvgRank(@Param("sid") String sid, @Param("cat") String cat);

    // ─── Session management ───────────────────────────────────────────────────

    /** Distinct session IDs ordered newest first */
    @Query("SELECT DISTINCT c.sessionId FROM CollegeCutoff c ORDER BY c.sessionId DESC")
    List<String> findDistinctSessionIds();

    /** Delete all records for a session */
    @Modifying
    @Transactional
    @Query("DELETE FROM CollegeCutoff c WHERE c.sessionId = :sessionId")
    void deleteBySessionId(@Param("sessionId") String sessionId);
}
