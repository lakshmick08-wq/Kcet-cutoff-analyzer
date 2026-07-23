package com.kcet.analyzer.controller;

import com.kcet.analyzer.model.CollegeCutoff;
import com.kcet.analyzer.service.CutoffService;
import com.kcet.analyzer.service.PdfParserService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * CutoffController — REST API for the KCET Cutoff Analyzer
 *
 * Endpoints:
 *   POST   /api/upload                      – Upload and parse a KCET data file
 *   GET    /api/categories/{sessionId}      – List distinct categories for a session (NEW)
 *   GET    /api/cutoffs/{sessionId}         – Paginated, filtered, sorted results
 *   GET    /api/export/{sessionId}          – Full data for CSV/Excel export
 *   GET    /api/stats/{sessionId}           – Statistics for a session + category
 *   GET    /api/sessions                    – List all session IDs
 *   DELETE /api/sessions/{sessionId}        – Delete a session's data
 *   GET    /api/health                      – Health check
 *
 * All category-scoped endpoints default to "GM" when ?category is omitted.
 * CORS is open (*) to allow the static frontend to call from any origin.
 */
@RestController
@RequestMapping("/api")
@CrossOrigin(origins = "*", allowedHeaders = "*")
public class CutoffController {

    private final PdfParserService parserService;
    private final CutoffService    cutoffService;

    public CutoffController(PdfParserService parserService, CutoffService cutoffService) {
        this.parserService = parserService;
        this.cutoffService = cutoffService;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Upload & Parse
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * POST /api/upload
     * Body: multipart/form-data, field "file"
     * Returns: { sessionId, stats, recordCount, fileName, fileType, message }
     *
     * stats contains counts for the default GM category so the dashboard
     * populates immediately after upload.
     */
    @PostMapping("/upload")
    public ResponseEntity<Map<String, Object>> uploadFile(
            @RequestParam("file") MultipartFile file) {

        try {
            Map<String, Object> result = parserService.processUpload(file);
            return ResponseEntity.ok(result);

        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest()
                .body(errorBody(e.getMessage()));

        } catch (IOException e) {
            return ResponseEntity.internalServerError()
                .body(errorBody("File I/O error: " + e.getMessage()));

        } catch (RuntimeException e) {
            return ResponseEntity.internalServerError()
                .body(errorBody(e.getMessage()));
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Categories  (NEW)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * GET /api/categories/{sessionId}
     *
     * Returns a JSON array of distinct category codes available in this session,
     * sorted with GM first then alphabetical.
     *
     * Example response: ["GM","1G","1R","2AG","2AR","2BG","2BR","3AG","SCG","STG",…]
     *
     * The frontend uses this to populate the category dropdown dynamically
     * without any hardcoded category list.
     */
    @GetMapping("/categories/{sessionId}")
    public ResponseEntity<List<String>> getCategories(
            @PathVariable String sessionId) {

        return ResponseEntity.ok(cutoffService.getCategories(sessionId));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Query / Filter / Sort / Paginate
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * GET /api/cutoffs/{sessionId}
     *
     * Query params:
     *   category – merit category to filter by (default GM)
     *   maxRank  – maximum cutoff rank; 0 = show all (default 0)
     *   search   – free-text on college or branch name
     *   branch   – branch keyword filter
     *   sort     – rank_asc | rank_desc | college | branch  (default rank_asc)
     *   page     – 0-based page number (default 0)
     *   size     – page size (default 20, max 100)
     */
    @GetMapping("/cutoffs/{sessionId}")
    public ResponseEntity<Map<String, Object>> getCutoffs(
            @PathVariable  String  sessionId,
            @RequestParam(defaultValue = "GM")        String  category,
            @RequestParam(defaultValue = "0")         Integer maxRank,
            @RequestParam(defaultValue = "")          String  search,
            @RequestParam(defaultValue = "")          String  branch,
            @RequestParam(defaultValue = "rank_asc")  String  sort,
            @RequestParam(defaultValue = "0")         int     page,
            @RequestParam(defaultValue = "20")        int     size) {

        size = Math.min(size, 100);

        Map<String, Object> result =
            cutoffService.getCutoffs(sessionId, category, maxRank, search, branch, sort, page, size);

        return ResponseEntity.ok(result);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Export
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * GET /api/export/{sessionId}
     * Returns ALL matching records (no pagination) for client-side CSV/Excel export.
     *
     * Query params:
     *   category – merit category (default GM)
     *   maxRank  – max cutoff rank; 0 = show all
     *   search   – college/branch free-text filter
     *   branch   – branch keyword filter
     */
    @GetMapping("/export/{sessionId}")
    public ResponseEntity<List<CollegeCutoff>> exportData(
            @PathVariable  String  sessionId,
            @RequestParam(defaultValue = "GM") String  category,
            @RequestParam(defaultValue = "0")  Integer maxRank,
            @RequestParam(defaultValue = "")   String  search,
            @RequestParam(defaultValue = "")   String  branch) {

        List<CollegeCutoff> data =
            cutoffService.getAllForExport(sessionId, category, maxRank, search, branch);

        return ResponseEntity.ok(data);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Statistics
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * GET /api/stats/{sessionId}?category=GM
     *
     * Returns statistics scoped to the selected category:
     *   category, totalRecords, cseCount, aimlCount, btCount,
     *   highestRank, lowestRank, averageRank
     */
    @GetMapping("/stats/{sessionId}")
    public ResponseEntity<Map<String, Object>> getStats(
            @PathVariable String sessionId,
            @RequestParam(defaultValue = "GM") String category) {

        Map<String, Object> stats = cutoffService.computeStats(sessionId, category);
        return ResponseEntity.ok(stats);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Session management
    // ─────────────────────────────────────────────────────────────────────────

    /** GET /api/sessions — list all session IDs */
    @GetMapping("/sessions")
    public ResponseEntity<List<String>> getSessions() {
        return ResponseEntity.ok(cutoffService.getAllSessionIds());
    }

    /** DELETE /api/sessions/{sessionId} — remove a session and its records */
    @DeleteMapping("/sessions/{sessionId}")
    public ResponseEntity<Map<String, Object>> deleteSession(
            @PathVariable String sessionId) {

        cutoffService.deleteSession(sessionId);
        Map<String, Object> resp = new HashMap<>();
        resp.put("message", "Session " + sessionId + " deleted successfully.");
        return ResponseEntity.ok(resp);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Health check
    // ─────────────────────────────────────────────────────────────────────────

    @GetMapping("/health")
    public ResponseEntity<Map<String, String>> health() {
        return ResponseEntity.ok(Map.of("status", "ok", "app", "KCET Cutoff Analyzer"));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    private Map<String, Object> errorBody(String message) {
        Map<String, Object> map = new HashMap<>();
        map.put("error", message);
        return map;
    }
}
