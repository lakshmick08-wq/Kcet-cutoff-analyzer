package com.kcet.analyzer.service;

import com.kcet.analyzer.model.CollegeCutoff;
import com.kcet.analyzer.repository.CollegeCutoffRepository;
import com.kcet.analyzer.util.FileTextExtractorUtil;
import com.kcet.analyzer.util.PdfParserUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * PdfParserService — orchestrates the full file-upload pipeline:
 *   1. Validate the uploaded file (format + size)
 *   2. Save to disk
 *   3. Delegate text extraction + KCET parsing to PdfParserUtil
 *   4. Bulk-save records to MySQL
 *   5. Return session ID + statistics summary
 *
 * Accepted formats: PDF, Word (.doc/.docx), Excel (.xls/.xlsx),
 *                   HTML (.html/.htm), Plain Text (.txt)
 */
@Service
public class PdfParserService {

    private static final Logger log = LoggerFactory.getLogger(PdfParserService.class);

    /** 100 MB upload limit */
    private static final long MAX_FILE_SIZE = 100L * 1024 * 1024;

    private final PdfParserUtil          parserUtil;
    private final CollegeCutoffRepository repository;
    private final CutoffService          cutoffService;

    @Value("${app.upload.dir:uploads}")
    private String uploadDir;

    public PdfParserService(PdfParserUtil parserUtil,
                            CollegeCutoffRepository repository,
                            CutoffService cutoffService) {
        this.parserUtil    = parserUtil;
        this.repository    = repository;
        this.cutoffService = cutoffService;
    }

    /**
     * Full upload pipeline.
     *
     * @param file the multipart file uploaded by the user (any supported format)
     * @return map with keys: sessionId, stats (sub-map), message, recordCount, fileName, fileType
     */
    public Map<String, Object> processUpload(MultipartFile file) throws IOException {

        // ── 1. Validate ────────────────────────────────────────────────────────
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("Uploaded file is empty.");
        }

        String originalName = file.getOriginalFilename();
        if (!FileTextExtractorUtil.isSupported(originalName)) {
            throw new IllegalArgumentException(
                "Unsupported file format \"" + originalName + "\". " +
                "Accepted: PDF (.pdf), Word (.doc, .docx), " +
                "Excel (.xls, .xlsx), HTML (.html, .htm), Text (.txt)");
        }

        if (file.getSize() > MAX_FILE_SIZE) {
            throw new IllegalArgumentException(
                "File is too large (" + (file.getSize() / 1048576) + " MB). Maximum allowed size is 100 MB.");
        }

        // ── 2. Save to uploads/ directory ─────────────────────────────────────
        Path uploadPath = Paths.get(uploadDir);
        if (!Files.exists(uploadPath)) {
            Files.createDirectories(uploadPath);
        }

        String sessionId = UUID.randomUUID().toString().substring(0, 8)
                           + "-" + Instant.now().toEpochMilli();
        String safeName  = originalName.replaceAll("[^a-zA-Z0-9._-]", "_");
        String savedName = sessionId + "_" + safeName;
        Path   savedPath = uploadPath.resolve(savedName);

        file.transferTo(savedPath.toFile());
        log.info("Saved uploaded file to: {}", savedPath);

        // ── 3. Parse file ──────────────────────────────────────────────────────
        List<CollegeCutoff> records;
        try {
            records = parserUtil.parse(savedPath.toFile(), sessionId);
        } catch (Exception e) {
            Files.deleteIfExists(savedPath);
            throw new RuntimeException("File parsing failed: " + e.getMessage(), e);
        }

        if (records.isEmpty()) {
            Files.deleteIfExists(savedPath);
            throw new RuntimeException(
                "The file was read successfully, but no KCET cutoff records were found inside it. " +
                "It looks like this may not be a KCET cutoff data file. " +
                "Please upload the official KEA cutoff PDF (e.g. \"PROF_CODE_E_R_*.pdf\") " +
                "downloaded from kea.kar.nic.in — or a Word/Excel/HTML/TXT version of that same data. " +
                "The file must contain college headers like \"College: E001 ...\", " +
                "a \"Course Name\" column row, and branch rows with rank numbers.");
        }


        // ── 4. Bulk save to MySQL ──────────────────────────────────────────────
        repository.saveAll(records);
        log.info("Saved {} records for session {}", records.size(), sessionId);

        // ── 5. Build response ──────────────────────────────────────────────────
        Map<String, Object> stats = cutoffService.computeStats(sessionId);

        String extension = originalName.contains(".")
            ? originalName.substring(originalName.lastIndexOf('.') + 1).toUpperCase()
            : "UNKNOWN";

        Map<String, Object> response = new HashMap<>();
        response.put("sessionId",   sessionId);
        response.put("stats",       stats);
        response.put("recordCount", records.size());
        response.put("fileName",    originalName);
        response.put("fileType",    extension);
        response.put("message",     "Successfully parsed " + records.size() +
                                    " records from " + extension + " file.");

        return response;
    }
}
