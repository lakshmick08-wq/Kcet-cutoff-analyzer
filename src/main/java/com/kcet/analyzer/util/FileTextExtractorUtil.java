package com.kcet.analyzer.util;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.hwpf.HWPFDocument;
import org.apache.poi.hwpf.extractor.WordExtractor;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * FileTextExtractorUtil — Extracts plain text from multiple document formats.
 *
 * Supported formats:
 *   .pdf    → Apache PDFBox
 *   .docx   → Apache POI (XWPFDocument)
 *   .doc    → Apache POI (HWPFDocument / scratchpad)
 *   .xlsx   → Apache POI (XSSFWorkbook) — rows joined as space-separated tokens
 *   .xls    → Apache POI (HSSFWorkbook) — rows joined as space-separated tokens
 *   .html   → Jsoup — tables are extracted in row-per-line format to match PDF layout
 *   .htm    → same as .html
 *   .txt    → plain UTF-8 read
 *
 * All extractors return text in the same line-per-row format expected by
 * KcetCutoffParserUtil (i.e., the same layout produced by PDFTextStripper).
 */
@Component
public class FileTextExtractorUtil {

    private static final Logger log = LoggerFactory.getLogger(FileTextExtractorUtil.class);

    // ── Supported extensions ───────────────────────────────────────────────────
    public static final List<String> SUPPORTED_EXTENSIONS = List.of(
        ".pdf", ".docx", ".doc", ".xlsx", ".xls", ".html", ".htm", ".txt"
    );

    /**
     * Returns true if the given filename has a supported extension.
     */
    public static boolean isSupported(String filename) {
        if (filename == null) return false;
        String lower = filename.toLowerCase();
        return SUPPORTED_EXTENSIONS.stream().anyMatch(lower::endsWith);
    }

    /**
     * Extracts full text from the given file.
     *
     * @param file the source file
     * @return multi-line text string ready for parsing
     * @throws IOException on read errors
     * @throws IllegalArgumentException for unsupported file types
     */
    public String extractText(File file) throws IOException {
        String name = file.getName().toLowerCase();
        log.info("Extracting text from: {} ({})", file.getName(),
                 humanReadableSize(file.length()));

        if (name.endsWith(".pdf"))           return extractFromPdf(file);
        if (name.endsWith(".docx"))          return extractFromDocx(file);
        if (name.endsWith(".doc"))           return extractFromDoc(file);
        if (name.endsWith(".xlsx"))          return extractFromXlsx(file);
        if (name.endsWith(".xls"))           return extractFromXls(file);
        if (name.endsWith(".html")
         || name.endsWith(".htm"))           return extractFromHtml(file);
        if (name.endsWith(".txt"))           return extractFromTxt(file);

        throw new IllegalArgumentException(
            "Unsupported file format. Accepted: PDF, Word (.doc/.docx), " +
            "Excel (.xls/.xlsx), HTML (.html/.htm), Text (.txt)");
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  PDF
    // ─────────────────────────────────────────────────────────────────────────

    private String extractFromPdf(File file) throws IOException {
        try (PDDocument doc = Loader.loadPDF(file)) {
            log.info("PDF pages: {}", doc.getNumberOfPages());
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            return stripper.getText(doc);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  WORD — .docx (Open XML)
    // ─────────────────────────────────────────────────────────────────────────

    private String extractFromDocx(File file) throws IOException {
        try (FileInputStream fis = new FileInputStream(file);
             XWPFDocument doc = new XWPFDocument(fis);
             XWPFWordExtractor extractor = new XWPFWordExtractor(doc)) {
            String text = extractor.getText();
            log.info("DOCX extracted {} chars", text.length());
            return text;
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  WORD — .doc (Binary / legacy)
    // ─────────────────────────────────────────────────────────────────────────

    private String extractFromDoc(File file) throws IOException {
        try (FileInputStream fis = new FileInputStream(file);
             HWPFDocument doc = new HWPFDocument(fis);
             WordExtractor extractor = new WordExtractor(doc)) {
            String text = extractor.getText();
            log.info("DOC extracted {} chars", text.length());
            return text;
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  EXCEL — .xlsx (Open XML)
    // ─────────────────────────────────────────────────────────────────────────

    private String extractFromXlsx(File file) throws IOException {
        try (FileInputStream fis = new FileInputStream(file);
             XSSFWorkbook wb = new XSSFWorkbook(fis)) {
            log.info("XLSX sheets: {}", wb.getNumberOfSheets());
            return extractFromWorkbook(wb);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  EXCEL — .xls (Binary / legacy)
    // ─────────────────────────────────────────────────────────────────────────

    private String extractFromXls(File file) throws IOException {
        try (FileInputStream fis = new FileInputStream(file);
             HSSFWorkbook wb = new HSSFWorkbook(fis)) {
            log.info("XLS sheets: {}", wb.getNumberOfSheets());
            return extractFromWorkbook(wb);
        }
    }

    /**
     * Converts a Workbook (XLS or XLSX) to line-per-row text.
     *
     * Each row is joined with a single space so that:
     *   ["Computer Science And Engineering", "399", "--", "585", "153"] →
     *   "Computer Science And Engineering 399 -- 585 153"
     *
     * Empty cells are replaced with "--" (the KCET dash placeholder) to keep
     * column alignment consistent with the PDF format the parser expects.
     */
    private String extractFromWorkbook(Workbook wb) {
        FormulaEvaluator evaluator = wb.getCreationHelper().createFormulaEvaluator();
        StringBuilder sb = new StringBuilder();

        for (int s = 0; s < wb.getNumberOfSheets(); s++) {
            Sheet sheet = wb.getSheetAt(s);
            for (Row row : sheet) {
                if (row == null) continue;

                List<String> cells = new ArrayList<>();
                int lastCol = row.getLastCellNum();

                for (int c = 0; c < lastCol; c++) {
                    Cell cell = row.getCell(c, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
                    cells.add(cellToString(cell, evaluator));
                }

                // Remove trailing empty cells, then join
                while (!cells.isEmpty() && cells.get(cells.size() - 1).equals("--")) {
                    cells.remove(cells.size() - 1);
                }

                if (!cells.isEmpty()) {
                    sb.append(String.join(" ", cells)).append("\n");
                }
            }
        }

        log.info("Workbook extracted {} chars", sb.length());
        return sb.toString();
    }

    private String cellToString(Cell cell, FormulaEvaluator evaluator) {
        if (cell == null) return "--";

        CellType type = cell.getCellType();
        if (type == CellType.FORMULA) {
            try {
                CellValue cv = evaluator.evaluate(cell);
                type = cv.getCellType();
                if (type == CellType.NUMERIC) {
                    double d = cv.getNumberValue();
                    return formatNumeric(d);
                }
                if (type == CellType.STRING)  return cv.getStringValue().trim();
                if (type == CellType.BOOLEAN) return String.valueOf(cv.getBooleanValue());
            } catch (Exception e) {
                return "--";
            }
        }
        if (type == CellType.NUMERIC) {
            return formatNumeric(cell.getNumericCellValue());
        }
        if (type == CellType.STRING) {
            String v = cell.getStringCellValue().trim();
            return v.isEmpty() ? "--" : v;
        }
        if (type == CellType.BOOLEAN) {
            return String.valueOf(cell.getBooleanCellValue());
        }
        return "--"; // BLANK, ERROR
    }

    /** Format a numeric cell value: integer if whole number, decimal otherwise. */
    private String formatNumeric(double d) {
        if (d == 0.0) return "--";
        if (d == Math.floor(d) && !Double.isInfinite(d)) {
            return String.valueOf((long) d);
        }
        return String.valueOf(d);
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  HTML — .html / .htm
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Extracts text from HTML.
     *
     * Strategy:
     *   1. If the HTML has &lt;table&gt; elements, reconstruct them row-by-row
     *      (each &lt;tr&gt; → one line, cells joined with spaces). This preserves
     *      the tabular layout that the KCET parser expects.
     *   2. Otherwise, fall back to Jsoup's full body text extraction.
     */
    private String extractFromHtml(File file) throws IOException {
        Document doc = Jsoup.parse(file, "UTF-8");
        Elements tables = doc.select("table");

        StringBuilder sb = new StringBuilder();

        // Append any text before first table (may contain college headers)
        appendNonTableText(doc, sb);

        if (!tables.isEmpty()) {
            for (Element table : tables) {
                for (Element row : table.select("tr")) {
                    List<String> cells = new ArrayList<>();
                    for (Element cell : row.select("td, th")) {
                        String text = cell.text().trim();
                        if (!text.isEmpty()) cells.add(text);
                        else cells.add("--");
                    }
                    if (!cells.isEmpty()) {
                        sb.append(String.join(" ", cells)).append("\n");
                    }
                }
                sb.append("\n");
            }
        } else {
            // No tables — extract all body text line by line
            sb.append(doc.body().wholeText());
        }

        log.info("HTML extracted {} chars", sb.length());
        return sb.toString();
    }

    /** Append text nodes that live outside table elements (e.g. college name headings). */
    private void appendNonTableText(Document doc, StringBuilder sb) {
        // Paragraphs and headings outside tables often hold college names
        for (Element el : doc.select("p, h1, h2, h3, h4, h5, h6")) {
            if (el.parents().select("table").isEmpty()) {
                String text = el.text().trim();
                if (!text.isEmpty()) sb.append(text).append("\n");
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  PLAIN TEXT — .txt
    // ─────────────────────────────────────────────────────────────────────────

    private String extractFromTxt(File file) throws IOException {
        byte[] bytes = Files.readAllBytes(file.toPath());
        // Try UTF-8 first, then fall back to system default
        String text;
        try {
            text = new String(bytes, StandardCharsets.UTF_8);
        } catch (Exception e) {
            text = new String(bytes);
        }
        log.info("TXT extracted {} chars", text.length());
        return text;
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  HELPERS
    // ─────────────────────────────────────────────────────────────────────────

    private String humanReadableSize(long bytes) {
        if (bytes < 1024)       return bytes + " B";
        if (bytes < 1048576)    return (bytes / 1024) + " KB";
        return (bytes / 1048576) + " MB";
    }
}
