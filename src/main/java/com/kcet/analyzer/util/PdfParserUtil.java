package com.kcet.analyzer.util;

import com.kcet.analyzer.model.CollegeCutoff;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.File;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * KcetCutoffParser — Parses KCET cutoff data from the plain text produced
 * by {@link FileTextExtractorUtil}.
 *
 * Format-agnostic: receives plain text (from PDF, Word, Excel, HTML or TXT)
 * and uses a state machine to extract one CollegeCutoff record per
 * (college, branch, category) triple.
 *
 * ── HOW IT WORKS ──
 *
 * The KCET cutoff document has this structure:
 *
 *   College: E001 University of Visvesvaraya College of Engineering …
 *   Course Name 1G 1K 1R 2AG 2AK 2AR 2BG 2BK 2BR 3AG 3AK 3AR 3BG 3BK 3BR GM GMK GMP GMR NRI OPN OTH …
 *   ARTIFICIAL 6638 -- -- 6041 -- 9103.5 …
 *   INTELLIGENCE
 *   AND DATA
 *   SCIENCE
 *   CIVIL 58975 -- 69434 52225 …
 *   ENGINEERING
 *   BIO-TECHNOLOGY 4510 -- -- 4779 …
 *
 * The "Course Name" row is parsed to extract ALL category headers
 * (e.g. ["1G","1K","1R","2AG","2AK","2AR","2BG","2BK","2BR",…,"GM","GMK","GMP",…,"STG","STK","STR"]).
 *
 * For each branch row, ALL non-dash columns emit a separate CollegeCutoff record.
 * A PDF with 85 colleges × 20 branches × (avg) 10 categories ≈ 17,000 records.
 *
 * No category whitelist is used — every column in the PDF is captured automatically.
 */
@Component
public class PdfParserUtil {

    private static final Logger log = LoggerFactory.getLogger(PdfParserUtil.class);

    private final FileTextExtractorUtil extractor;

    public PdfParserUtil(FileTextExtractorUtil extractor) {
        this.extractor = extractor;
    }

    // ─── Patterns ─────────────────────────────────────────────────────────────

    /** College header: "College: E001 Name..." */
    private static final Pattern COLLEGE_HDR = Pattern.compile(
        "^College:\\s*([A-Z]\\d{3,4})\\s+(.*)",
        Pattern.CASE_INSENSITIVE
    );

    /** Column header row starts with "Course Name" */
    private static final Pattern COURSE_NAME_HDR = Pattern.compile(
        "^Course\\s+Name\\b",
        Pattern.CASE_INSENSITIVE
    );

    /** A data token: integer, decimal, or dash placeholder ("6904", "9103.5", "--", "---") */
    private static final Pattern DATA_TOKEN = Pattern.compile(
        "^(\\d+(\\.\\d+)?|-+)$"
    );

    /** Lines to skip (page footer / document metadata) */
    private static final Pattern SKIP_LINE = Pattern.compile(
        "^(GENERATED ON|KARNATAKA|NON-INTERACTIVE|UGCET|SEAT TYPE|PAGE \\d)",
        Pattern.CASE_INSENSITIVE
    );

    // ─────────────────────────────────────────────────────────────────────────
    //  PUBLIC API
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Parse any supported file and return a list of CollegeCutoff records.
     *
     * Text extraction is delegated to {@link FileTextExtractorUtil};
     * the KCET-specific parsing logic runs on the resulting plain text.
     *
     * Returns one record per (college, branch, category) — so a branch with
     * 10 non-dash columns produces 10 records in the output list.
     */
    public List<CollegeCutoff> parse(File file, String sessionId) {
        List<CollegeCutoff> results = new ArrayList<>();

        try {
            String fullText = extractor.extractText(file);
            parseAllText(fullText, sessionId, results);
        } catch (Exception e) {
            log.error("Parsing failed for {}: {}", file.getName(), e.getMessage(), e);
            throw new RuntimeException("Failed to parse file: " + e.getMessage(), e);
        }

        log.info("Parsing complete. Total records: {} (college+branch+category triples)", results.size());
        return results;
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  CORE STATE-MACHINE PARSER
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * State machine:
     *
     *   collegeCode / collegeName  — set when a "College: Exxx ..." line is detected.
     *   categoryHeaders            — ordered list of column names from the "Course Name ..." row.
     *   branchBuf                  — accumulates branch name fragments across multiple lines.
     *   pendingDataToks            — data tokens from the last "mixed" line (branch + data).
     *
     * Line patterns:
     *
     *   [A] College header:   "College: E001 Univesity of Visvesvaraya..."
     *   [B] Column header:    "Course Name 1G 1K 1R 2AG 2AK 2AR ... GM GMK GMP GMR ... STG STK STR"
     *   [C] Mixed line:       "ARTIFICIAL 6638 -- -- 6041 -- 9103.5 ..."   (branch start + all data)
     *                         "CIVIL 58975 -- 69434 52225 ..."
     *                         "BIO-TECHNOLOGY 4510 -- -- 4779 ..."
     *   [D] Pure text:        "INTELLIGENCE"  "AND DATA"  "SCIENCE"        (branch name continuation)
     *   [E] Footer:           "Generated on: 06-07-2026 18:18:46 Page 1 of 69"
     *
     * On [C]: flush any previous pending branch+data, save new text prefix to branchBuf,
     *         save data tokens to pendingDataToks.
     * On [D]: append text words to branchBuf (extends the branch name).
     * On [A]/[B]/next [C]: flush the pending branch+data into results, then reset.
     */
    private void parseAllText(String fullText, String sessionId, List<CollegeCutoff> results) {
        String[] lines = fullText.split("\\r?\\n");

        String       collegeCode      = null;
        String       collegeName      = null;
        List<String> categoryHeaders  = new ArrayList<>();  // populated from Course Name row
        List<String> branchBuf        = new ArrayList<>();
        String[]     pendingDataToks  = null;

        for (String rawLine : lines) {
            String line  = rawLine.trim();
            if (line.isEmpty()) continue;
            String upper = line.toUpperCase(Locale.ROOT);

            // ── Skip page footers / metadata ──────────────────────────────────
            if (SKIP_LINE.matcher(upper).find()) continue;

            // ── [A] College header ────────────────────────────────────────────
            Matcher hm = COLLEGE_HDR.matcher(line);
            if (hm.find()) {
                flushPending(branchBuf, pendingDataToks, categoryHeaders,
                             sessionId, collegeCode, collegeName, results);
                branchBuf.clear();
                pendingDataToks = null;

                collegeCode = hm.group(1).toUpperCase(Locale.ROOT);
                collegeName = hm.group(2).trim();
                log.debug("College: {} | {}", collegeCode, collegeName);
                continue;
            }

            // ── [B] Column header row ─────────────────────────────────────────
            if (COURSE_NAME_HDR.matcher(upper).find()) {
                flushPending(branchBuf, pendingDataToks, categoryHeaders,
                             sessionId, collegeCode, collegeName, results);
                categoryHeaders = parseCategoryHeaders(upper);
                branchBuf.clear();
                pendingDataToks = null;
                log.debug("Column header — {} categories: {}", categoryHeaders.size(), categoryHeaders);
                continue;
            }

            // ── No college context yet — skip ─────────────────────────────────
            if (collegeName == null) continue;

            // ── Classify line tokens ──────────────────────────────────────────
            String[] tokens    = line.split("\\s+");
            int      firstData = findFirstDataTokenIndex(tokens);

            if (firstData < 0) {
                // ── [D] Pure text — branch name continuation ──────────────────
                branchBuf.add(upper);

            } else if (firstData == 0) {
                // ── Pure data row — flush pending and discard ─────────────────
                flushPending(branchBuf, pendingDataToks, categoryHeaders,
                             sessionId, collegeCode, collegeName, results);
                branchBuf.clear();
                pendingDataToks = null;

            } else {
                // ── [C] Mixed line: text prefix + data tokens ─────────────────
                flushPending(branchBuf, pendingDataToks, categoryHeaders,
                             sessionId, collegeCode, collegeName, results);

                String[] txtToks  = Arrays.copyOfRange(tokens, 0, firstData);
                String[] dataToks = Arrays.copyOfRange(tokens, firstData, tokens.length);

                branchBuf.clear();
                branchBuf.add(String.join(" ", txtToks).toUpperCase(Locale.ROOT));
                pendingDataToks = dataToks;
            }
        }

        // ── End of file: flush remaining pending branch ───────────────────────
        flushPending(branchBuf, pendingDataToks, categoryHeaders,
                     sessionId, collegeCode, collegeName, results);
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  FLUSH: emit one CollegeCutoff per non-dash category column
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * For the accumulated (branchBuf, dataToks) pair, iterate every category
     * column and emit one CollegeCutoff record for each non-dash value.
     *
     * This replaces the old single-record (GM only) approach.
     *
     * Validation:
     *   - Branch name must be at least 3 chars and not a junk word.
     *   - Rank must be a valid integer in [100, 300000].
     *   - categoryHeaders must be non-empty (set from the Course Name row).
     */
    private void flushPending(List<String>        branchBuf,
                               String[]            dataToks,
                               List<String>        categoryHeaders,
                               String              sessionId,
                               String              collegeCode,
                               String              collegeName,
                               List<CollegeCutoff> results) {

        if (branchBuf.isEmpty() || dataToks == null || collegeName == null) return;
        if (categoryHeaders.isEmpty()) return;

        String branch = joinBuffer(branchBuf);
        if (branch.length() < 3)      return;
        if (isJunkBranchName(branch)) return;

        String displayBranch = toTitleCase(branch);
        int emitted = 0;

        for (int i = 0; i < Math.min(dataToks.length, categoryHeaders.size()); i++) {
            Integer rank = parseRank(dataToks[i]);
            if (rank != null) {
                results.add(new CollegeCutoff(
                    sessionId, collegeCode, collegeName,
                    displayBranch, categoryHeaders.get(i), rank
                ));
                emitted++;
            }
        }

        if (emitted > 0) {
            log.debug("  ✓ {} | {} | {} categories emitted", collegeName, displayBranch, emitted);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  HELPERS
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Parse the "Course Name 1G 1K 1R 2AG ... GM GMK ... STG STK STR" header row.
     *
     * Skips "COURSE" and "NAME" tokens, then collects every remaining token
     * as a category code. Returns the ordered list (preserving PDF column order).
     *
     * Examples of categories found: 1G, 1K, 1R, 2AG, 2AK, 2AR, 2BG, 2BK, 2BR,
     * 3AG, 3AK, 3AR, 3BG, 3BK, 3BR, GM, GMK, GMP, GMR, NRI, OPN, OTH,
     * S1G, S1K, S1R, S2G, S2K, S2R, S3G, S3K, S3R, S4G, S4K, S4R, STG, STK, STR.
     */
    private List<String> parseCategoryHeaders(String upperLine) {
        String[] toks = upperLine.split("\\s+");
        List<String> headers = new ArrayList<>();
        boolean pastCourseName = false;

        for (String tok : toks) {
            if (!pastCourseName) {
                if ("COURSE".equals(tok) || "NAME".equals(tok)) continue;
                pastCourseName = true;
            }
            // Accept tokens that look like category codes (alphanumeric, 1–8 chars)
            if (tok.matches("[A-Z0-9]{1,8}")) {
                headers.add(tok);
            }
        }
        return headers;
    }

    /**
     * Reject strings that are clearly not branch names — orphaned text lines
     * that appear as continuation tokens but should not stand alone.
     */
    private boolean isJunkBranchName(String upper) {
        Set<String> junk = Set.of(
            "AND", "OR", "OF", "IN", "FOR", "THE", "A", "AN",
            "ENGINEERING", "ENGG", "TECHNOLOGY", "SCIENCE",
            "MANAGEMENT", "STUDIES"
        );
        return junk.contains(upper.trim());
    }

    /**
     * Returns the index of the FIRST data token (number or dash) in the array.
     * Returns -1 if all tokens are text.
     */
    private int findFirstDataTokenIndex(String[] tokens) {
        for (int i = 0; i < tokens.length; i++) {
            if (DATA_TOKEN.matcher(tokens[i]).matches()) return i;
        }
        return -1;
    }

    /**
     * Parse a token as a rank (integer or decimal rounded to int).
     * Returns null for dash tokens ("--", "---") or out-of-range values.
     * Valid rank range: [100, 300000].
     */
    private Integer parseRank(String token) {
        if (token == null || token.startsWith("-")) return null;
        try {
            double d = Double.parseDouble(token);
            int v = (int) Math.round(d);
            return (v >= 100 && v <= 300000) ? v : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Join buffer fragments and normalise spaces/hyphens. */
    private String joinBuffer(List<String> buf) {
        return String.join(" ", buf)
            .replaceAll("\\s+", " ")
            .replaceAll("-\\s+", "-")
            .replaceAll("\\s+-", "-")
            .trim();
    }

    /** Convert UPPERCASE branch name to Title Case for display. */
    private String toTitleCase(String upper) {
        String[] words = upper.split("\\s+");
        StringBuilder sb = new StringBuilder();
        for (String word : words) {
            if (word.isEmpty()) continue;
            if (word.contains("-")) {
                String[] parts = word.split("-", -1);
                for (int i = 0; i < parts.length; i++) {
                    if (parts[i].isEmpty()) { sb.append("-"); continue; }
                    sb.append(Character.toUpperCase(parts[i].charAt(0)));
                    if (parts[i].length() > 1)
                        sb.append(parts[i].substring(1).toLowerCase(Locale.ROOT));
                    if (i < parts.length - 1) sb.append("-");
                }
            } else {
                sb.append(Character.toUpperCase(word.charAt(0)));
                if (word.length() > 1)
                    sb.append(word.substring(1).toLowerCase(Locale.ROOT));
            }
            sb.append(' ');
        }
        return sb.toString().trim();
    }
}
