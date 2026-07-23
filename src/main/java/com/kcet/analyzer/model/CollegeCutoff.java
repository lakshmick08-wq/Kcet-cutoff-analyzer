package com.kcet.analyzer.model;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * CollegeCutoff — JPA Entity
 *
 * Represents one (college, branch, category, cutoffRank) tuple extracted from a
 * KCET cutoff file. Every non-dash column in the PDF produces a separate record,
 * so a single (college, branch) combination generates multiple rows — one per
 * merit category (GM, 2AG, 2BG, SCG, STG, 1G, GMK, …).
 *
 * Categories are detected dynamically from the "Course Name 1G 1K 1R …" header
 * row in the source document; nothing is hardcoded.
 *
 * Each upload session gets a UUID (sessionId) so multiple uploads can
 * coexist in the database without collisions.
 */
@Entity
@Table(name = "college_cutoffs", indexes = {
    @Index(name = "idx_session_id",   columnList = "session_id"),
    @Index(name = "idx_category",     columnList = "category"),
    @Index(name = "idx_cutoff_rank",  columnList = "cutoff_rank"),
    @Index(name = "idx_branch_name",  columnList = "branch_name"),
    @Index(name = "idx_sess_cat",     columnList = "session_id, category")
})
public class CollegeCutoff {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** UUID identifying the upload session this record belongs to */
    @Column(name = "session_id", nullable = false, length = 50)
    private String sessionId;

    /** College code as printed in the PDF (e.g. E001, E002) */
    @Column(name = "college_code", length = 20)
    private String collegeCode;

    /** Full college name */
    @Column(name = "college_name", nullable = false, length = 300)
    private String collegeName;

    /** Branch / course name in Title Case */
    @Column(name = "branch_name", nullable = false, length = 300)
    private String branchName;

    /**
     * Merit category code, exactly as it appears in the PDF column header.
     * Examples: GM, 2AG, 2BG, 3AG, SCG, STG, 1G, GMK, GMR, NRI, OPN, STK, …
     * Detected dynamically — no hardcoded category list.
     */
    @Column(name = "category", nullable = false, length = 20)
    private String category;

    /** Closing rank for this (college, branch, category) combination */
    @Column(name = "cutoff_rank", nullable = false)
    private int cutoffRank;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }

    // ─── Constructors ────────────────────────────────────────────────────────

    public CollegeCutoff() {}

    public CollegeCutoff(String sessionId, String collegeCode,
                         String collegeName, String branchName,
                         String category, int cutoffRank) {
        this.sessionId   = sessionId;
        this.collegeCode = collegeCode;
        this.collegeName = collegeName;
        this.branchName  = branchName;
        this.category    = category;
        this.cutoffRank  = cutoffRank;
    }

    // ─── Getters & Setters ───────────────────────────────────────────────────

    public Long    getId()                  { return id; }
    public void    setId(Long id)           { this.id = id; }

    public String  getSessionId()           { return sessionId; }
    public void    setSessionId(String v)   { this.sessionId = v; }

    public String  getCollegeCode()         { return collegeCode; }
    public void    setCollegeCode(String v) { this.collegeCode = v; }

    public String  getCollegeName()         { return collegeName; }
    public void    setCollegeName(String v) { this.collegeName = v; }

    public String  getBranchName()          { return branchName; }
    public void    setBranchName(String v)  { this.branchName = v; }

    public String  getCategory()            { return category; }
    public void    setCategory(String v)    { this.category = v; }

    public int     getCutoffRank()          { return cutoffRank; }
    public void    setCutoffRank(int v)     { this.cutoffRank = v; }

    public LocalDateTime getCreatedAt()     { return createdAt; }
}
