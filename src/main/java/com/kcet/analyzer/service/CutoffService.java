package com.kcet.analyzer.service;

import com.kcet.analyzer.model.CollegeCutoff;
import com.kcet.analyzer.repository.CollegeCutoffRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * CutoffService — Business Logic Layer
 *
 * All methods are now category-aware. When no category is supplied by the
 * caller, the default is "GM" for backward compatibility.
 *
 * Categories are returned from the database dynamically — no hardcoded lists.
 */
@Service
public class CutoffService {

    /** Default category when the caller omits the parameter */
    public static final String DEFAULT_CATEGORY = "GM";

    private final CollegeCutoffRepository repository;

    public CutoffService(CollegeCutoffRepository repository) {
        this.repository = repository;
    }

    // ─── Categories ──────────────────────────────────────────────────────────

    /**
     * Returns all distinct categories for a session, sorted with GM first
     * then the rest in alphabetical order.
     */
    public List<String> getCategories(String sessionId) {
        List<String> cats = new ArrayList<>(
            repository.findDistinctCategoriesBySessionId(sessionId)
        );
        cats.sort((a, b) -> {
            if ("GM".equals(a)) return -1;
            if ("GM".equals(b)) return  1;
            return a.compareTo(b);
        });
        return cats;
    }

    // ─── Query with pagination + filters ─────────────────────────────────────

    /**
     * Returns a paginated, filtered, sorted page of cutoff records for
     * the given session and category.
     *
     * @param sessionId   upload session UUID
     * @param category    merit category code (e.g. "GM", "2AG", "SCG"); defaults to GM
     * @param maxRank     maximum cutoff rank to show (0 = show all)
     * @param search      free-text search on college or branch name
     * @param branch      branch keyword filter
     * @param sortBy      rank_asc | rank_desc | college | branch
     * @param page        0-based page number
     * @param size        records per page
     */
    public Map<String, Object> getCutoffs(String  sessionId,
                                          String  category,
                                          Integer maxRank,
                                          String  search,
                                          String  branch,
                                          String  sortBy,
                                          int     page,
                                          int     size) {
        String   cat      = resolveCategory(category);
        Sort     sort     = buildSort(sortBy);
        Pageable pageable = PageRequest.of(page, size, sort);

        int    maxRankParam = (maxRank != null && maxRank > 0) ? maxRank : 0;
        String searchParam  = blankToEmpty(search);
        String branchParam  = blankToEmpty(branch);

        Page<CollegeCutoff> resultPage =
            repository.findByFilters(sessionId, cat, maxRankParam, searchParam, branchParam, pageable);

        Map<String, Object> response = new HashMap<>();
        response.put("content",       resultPage.getContent());
        response.put("totalElements", resultPage.getTotalElements());
        response.put("totalPages",    resultPage.getTotalPages());
        response.put("currentPage",   resultPage.getNumber());
        response.put("pageSize",      resultPage.getSize());
        response.put("first",         resultPage.isFirst());
        response.put("last",          resultPage.isLast());
        response.put("category",      cat);   // echo back resolved category

        return response;
    }

    // ─── Export (all matching records, no pagination) ─────────────────────────

    public List<CollegeCutoff> getAllForExport(String  sessionId,
                                               String  category,
                                               Integer maxRank,
                                               String  search,
                                               String  branch) {
        String cat = resolveCategory(category);
        int maxRankParam = (maxRank != null && maxRank > 0) ? maxRank : 0;
        return repository.findAllByFiltersForExport(
            sessionId, cat, maxRankParam, blankToEmpty(search), blankToEmpty(branch)
        );
    }

    // ─── Statistics ───────────────────────────────────────────────────────────

    /**
     * Computes statistics for a session scoped to the specified category.
     * All stat values (count, min, max, avg) are computed only on records
     * matching the chosen category.
     */
    public Map<String, Object> computeStats(String sessionId, String category) {
        String cat = resolveCategory(category);

        long    total     = repository.countBySessionIdAndCategory(sessionId, cat);
        long    cseCount  = repository.countBySessionCategoryAndBranch(sessionId, cat, "computer");
        long    aimlCount = repository.countBySessionCategoryAndBranch(sessionId, cat, "artificial intelligence");
        long    btCount   = repository.countBySessionCategoryAndBranch(sessionId, cat, "bio-technology");

        Integer highest = repository.findMaxRank(sessionId, cat);
        Integer lowest  = repository.findMinRank(sessionId, cat);
        Double  average = repository.findAvgRank(sessionId, cat);

        Map<String, Object> stats = new HashMap<>();
        stats.put("category",     cat);
        stats.put("totalRecords", total);
        stats.put("cseCount",     cseCount);
        stats.put("aimlCount",    aimlCount);
        stats.put("btCount",      btCount);
        stats.put("highestRank",  highest != null ? highest : 0);
        stats.put("lowestRank",   lowest  != null ? lowest  : 0);
        stats.put("averageRank",  average != null ? Math.round(average) : 0);

        return stats;
    }

    // ─── Session management ───────────────────────────────────────────────────

    public List<String> getAllSessionIds() {
        return repository.findDistinctSessionIds();
    }

    public void deleteSession(String sessionId) {
        repository.deleteBySessionId(sessionId);
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    /** Returns the category as-is (uppercased), defaulting to GM if blank/null. */
    private String resolveCategory(String category) {
        return (category != null && !category.isBlank())
               ? category.trim().toUpperCase(Locale.ROOT)
               : DEFAULT_CATEGORY;
    }

    private String blankToEmpty(String s) {
        return (s != null && !s.isBlank()) ? s.trim() : "";
    }

    /**
     * Builds a JPA Sort from the UI sort key.
     * rank_asc  → cutoffRank ASC  (lowest/easiest cutoffs first — most relevant for predictor)
     * rank_desc → cutoffRank DESC (highest cutoffs first)
     * college   → collegeName ASC
     * branch    → branchName ASC
     * Legacy gm_desc / gm_asc keys are mapped to rank equivalents.
     */
    private Sort buildSort(String sortBy) {
        if (sortBy == null) return Sort.by(Sort.Direction.ASC, "cutoffRank");
        return switch (sortBy.toLowerCase()) {
            case "rank_desc", "gm_desc" -> Sort.by(Sort.Direction.DESC, "cutoffRank");
            case "rank_asc",  "gm_asc"  -> Sort.by(Sort.Direction.ASC,  "cutoffRank");
            case "college"               -> Sort.by(Sort.Direction.ASC,  "collegeName");
            case "branch"                -> Sort.by(Sort.Direction.ASC,  "branchName");
            default                      -> Sort.by(Sort.Direction.ASC,  "cutoffRank");
        };
    }
}
