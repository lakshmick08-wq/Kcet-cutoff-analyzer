/**
 * KCET Cutoff Analyzer — script.js  (v4 — multi-category)
 *
 * Architecture:
 *   - All API calls go to BASE_URL (localhost:8081)
 *   - State is kept in the `state` object
 *   - renderTable() and renderStats() update the DOM
 *   - All filtering/sorting/pagination is server-side
 *   - Category dropdown is populated dynamically from /api/categories/{sessionId}
 */

'use strict';

// ─── Configuration ────────────────────────────────────────────────────────────
const BASE_URL = 'http://localhost:8081/api';

// ─── Application State ────────────────────────────────────────────────────────
const state = {
  sessionId:      null,
  currentPage:    0,
  pageSize:       20,
  totalPages:     0,
  totalElements:  0,
  sort:           'rank_asc',   // lowest cutoff first = default
  search:         '',
  branch:         '',
  category:       'GM',         // selected merit category
  maxRank:        0,            // 0 = show ALL colleges
  userRank:       0,            // user's entered rank for eligibility highlighting
};

// ─── DOM References ───────────────────────────────────────────────────────────
const $ = id => document.getElementById(id);

const uploadZone       = $('upload-zone');
const fileInput        = $('file-input');
const uploadMessage    = $('upload-message');
const progressWrap     = $('upload-progress-wrap');
const progressLabel    = $('progress-label');
const statsSection     = $('stats-section');
const resultsSection   = $('results-section');
const tableBody        = $('table-body');
const emptyState       = $('empty-state');
const tableLoading     = $('table-loading');
const paginationBar    = $('pagination-bar');
const pageInfo         = $('page-info');
const resultBadge      = $('result-count-badge');
const sessionWrap      = $('session-selector-wrap');
const sessionSelect    = $('session-select');
const categoryFilter   = $('category-filter');

// ─────────────────────────────────────────────────────────────────────────────
// INIT
// ─────────────────────────────────────────────────────────────────────────────
document.addEventListener('DOMContentLoaded', () => {
  initUploadZone();
  initSortButtons();
  initPagination();
  initFilters();
  initExport();
  initSessionSelector();
  loadExistingSessions();

  // Default sort: rank_asc (lowest cutoff first)
  document.querySelectorAll('.btn-sort').forEach(b => {
    b.classList.toggle('active',   b.dataset.sort === 'rank_asc');
    b.setAttribute('aria-pressed', b.dataset.sort === 'rank_asc' ? 'true' : 'false');
  });
});

// ─────────────────────────────────────────────────────────────────────────────
// UPLOAD ZONE
// ─────────────────────────────────────────────────────────────────────────────
function initUploadZone() {
  uploadZone.addEventListener('click', () => fileInput.click());
  uploadZone.addEventListener('keydown', e => {
    if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); fileInput.click(); }
  });

  fileInput.addEventListener('change', e => {
    const file = e.target.files?.[0];
    if (file) handleFileUpload(file);
    fileInput.value = '';
  });

  uploadZone.addEventListener('dragover', e => { e.preventDefault(); uploadZone.classList.add('drag-over'); });
  uploadZone.addEventListener('dragleave', () => uploadZone.classList.remove('drag-over'));
  uploadZone.addEventListener('drop', e => {
    e.preventDefault();
    uploadZone.classList.remove('drag-over');
    const file = e.dataTransfer.files?.[0];
    if (file) handleFileUpload(file);
  });
}

// ─────────────────────────────────────────────────────────────────────────────
// FILE UPLOAD → POST /api/upload
// ─────────────────────────────────────────────────────────────────────────────
async function handleFileUpload(file) {
  const SUPPORTED_EXTS = ['.pdf', '.docx', '.doc', '.xlsx', '.xls', '.html', '.htm', '.txt'];
  const ext = '.' + file.name.split('.').pop().toLowerCase();

  if (!SUPPORTED_EXTS.includes(ext)) {
    showUploadMessage(
      'Unsupported file format. Please upload a PDF, Word (.doc/.docx), Excel (.xls/.xlsx), HTML, or TXT file.',
      'error'
    );
    return;
  }
  if (file.size > 100 * 1024 * 1024) {
    showUploadMessage('File is too large. Maximum size is 100 MB.', 'error');
    return;
  }

  clearUploadMessage();
  progressWrap.style.display = 'block';
  progressLabel.textContent  = `Uploading "${file.name}" and parsing all categories…`;

  const formData = new FormData();
  formData.append('file', file);

  try {
    const res  = await fetch(`${BASE_URL}/upload`, { method: 'POST', body: formData });
    const data = await res.json();

    progressWrap.style.display = 'none';

    if (!res.ok) {
      showUploadMessage(data.error || 'Upload failed. Please try again.', 'error');
      return;
    }

    const fileType = data.fileType || 'FILE';
    const msg = `✓ Parsed ${data.recordCount} records from "${data.fileName}" (${fileType}). Session: ${data.sessionId}`;
    showUploadMessage(msg, 'success');

    state.sessionId   = data.sessionId;
    state.currentPage = 0;
    state.category    = 'GM';

    // Update stats for GM category (default)
    if (data.stats) renderStats(data.stats);

    // Add to session selector
    addSessionOption(data.sessionId, data.fileName, data.recordCount);
    sessionWrap.style.display = 'flex';
    sessionSelect.value = data.sessionId;

    // Load dynamic categories then results
    await loadCategories();
    await loadCutoffs();

  } catch (err) {
    progressWrap.style.display = 'none';
    showUploadMessage('Network error: Could not reach the server. Is the Spring Boot backend running on port 8081?', 'error');
    console.error('Upload error:', err);
  }
}

// ─────────────────────────────────────────────────────────────────────────────
// CATEGORIES → GET /api/categories/{sessionId}
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Populate the category dropdown from the backend.
 * No hardcoded category names — everything comes from the database.
 */
async function loadCategories() {
  if (!state.sessionId) return;

  try {
    const res  = await fetch(`${BASE_URL}/categories/${state.sessionId}`);
    const cats = await res.json();

    if (!res.ok || !Array.isArray(cats) || cats.length === 0) return;

    // Rebuild dropdown
    categoryFilter.innerHTML = '';
    cats.forEach(cat => {
      const opt   = document.createElement('option');
      opt.value   = cat;
      opt.text    = cat === 'GM'  ? 'GM — General Merit'
                  : cat === 'GMK' ? 'GMK — Kannada Medium'
                  : cat === 'GMR' ? 'GMR — Rural'
                  : cat === 'NRI' ? 'NRI'
                  : cat === 'OPN' ? 'OPN — Open'
                  : cat === 'SCG' ? 'SCG — Scheduled Caste'
                  : cat === 'SCR' ? 'SCR — SC Rural'
                  : cat === 'STG' ? 'STG — Scheduled Tribe'
                  : cat === 'STR' ? 'STR — ST Rural'
                  : cat;
      categoryFilter.appendChild(opt);
    });

    // Restore or default to GM
    categoryFilter.value = state.category || 'GM';
    categoryFilter.disabled = false;

    // Update table header
    updateCutoffHeader(state.category || 'GM');

  } catch (_) { /* silently ignore */ }
}

/** Update the table header column to reflect the selected category */
function updateCutoffHeader(category) {
  const th = $('th-cutoff');
  if (th) th.textContent = `${category} Cutoff Rank`;
}

// ─────────────────────────────────────────────────────────────────────────────
// LOAD CUTOFFS → GET /api/cutoffs/{sessionId}
// ─────────────────────────────────────────────────────────────────────────────
async function loadCutoffs() {
  if (!state.sessionId) return;

  tableBody.innerHTML = '';
  emptyState.style.display   = 'none';
  tableLoading.style.display = 'flex';
  resultsSection.style.display = 'block';

  const params = new URLSearchParams({
    category: state.category,
    maxRank:  state.maxRank,
    search:   state.search,
    branch:   state.branch,
    sort:     state.sort,
    page:     state.currentPage,
    size:     state.pageSize,
  });

  try {
    const res  = await fetch(`${BASE_URL}/cutoffs/${state.sessionId}?${params}`);
    const data = await res.json();

    tableLoading.style.display = 'none';

    if (!res.ok) {
      showUploadMessage(data.error || 'Failed to load results.', 'error');
      return;
    }

    state.totalPages    = data.totalPages;
    state.totalElements = data.totalElements;

    updateCutoffHeader(state.category);
    renderTable(data.content, data.currentPage);
    renderPagination(data);

  } catch (err) {
    tableLoading.style.display = 'none';
    console.error('Fetch error:', err);
  }
}

// ─────────────────────────────────────────────────────────────────────────────
// RENDER TABLE
// ─────────────────────────────────────────────────────────────────────────────
function renderTable(records, currentPage) {
  tableBody.innerHTML = '';

  if (!records || records.length === 0) {
    emptyState.style.display    = 'flex';
    paginationBar.style.display = 'none';
    resultBadge.textContent     = '0';
    const msgEl = $('empty-state-text');
    if (msgEl) {
      msgEl.innerHTML = state.userRank > 0
        ? `No colleges found for <strong>${state.category}</strong> rank <strong>${state.userRank.toLocaleString('en-IN')}</strong>. Try a higher rank or click <strong>Show All</strong>.`
        : `No records match your filters for category <strong>${state.category}</strong>.`;
    }
    return;
  }

  emptyState.style.display = 'none';

  const offset   = currentPage * state.pageSize;
  const fragment = document.createDocumentFragment();

  records.forEach((rec, i) => {
    const tr       = document.createElement('tr');
    const rowNum   = offset + i + 1;
    const cutoff   = rec.cutoffRank;   // ← new field name
    const rank     = state.userRank;

    // Colour class based on cutoff rank
    const rankClass = cutoff > 200000 ? 'gm-high'
                    : cutoff > 100000 ? 'gm-medium'
                    : 'gm-low';

    // Eligibility badge: rank <= cutoff means eligible
    let eligBadge = '';
    if (rank > 0) {
      if (rank <= cutoff) {
        eligBadge = `<span class="elig-badge elig-yes">✓ Eligible</span>`;
      } else if (rank <= cutoff + 5000) {
        eligBadge = `<span class="elig-badge elig-near">~ Borderline</span>`;
      } else {
        eligBadge = `<span class="elig-badge elig-no">✗ Not Eligible</span>`;
      }
    } else {
      eligBadge = `<span class="elig-badge elig-none">—</span>`;
    }

    tr.innerHTML = `
      <td class="row-number">${rowNum}</td>
      <td class="td-code">${escHtml(rec.collegeCode || '—')}</td>
      <td class="td-college">${escHtml(rec.collegeName || '—')}</td>
      <td><span class="branch-pill" title="${escHtml(rec.branchName || '')}">${escHtml(shortBranch(rec.branchName || ''))}</span></td>
      <td class="td-gm ${rankClass}">${cutoff != null ? cutoff.toLocaleString('en-IN') : '—'}</td>
      <td class="td-elig">${eligBadge}</td>
    `;
    fragment.appendChild(tr);
  });

  tableBody.appendChild(fragment);
  resultBadge.textContent = `${state.totalElements.toLocaleString('en-IN')} entries`;
}

// ─────────────────────────────────────────────────────────────────────────────
// RENDER STATS
// ─────────────────────────────────────────────────────────────────────────────
function renderStats(stats) {
  if (!stats) return;

  $('sv-total').textContent   = (stats.totalRecords ?? 0).toLocaleString('en-IN');
  $('sv-cse').textContent     = (stats.cseCount     ?? 0).toLocaleString('en-IN');
  $('sv-aiml').textContent    = (stats.aimlCount    ?? 0).toLocaleString('en-IN');
  $('sv-highest').textContent = (stats.highestRank  ?? 0) > 0 ? stats.highestRank.toLocaleString('en-IN') : '—';
  $('sv-lowest').textContent  = (stats.lowestRank   ?? 0) > 0 ? stats.lowestRank.toLocaleString('en-IN')  : '—';
  $('sv-avg').textContent     = (stats.averageRank  ?? 0) > 0 ? Math.round(stats.averageRank).toLocaleString('en-IN') : '—';

  statsSection.style.display = 'block';
  animateNumbers();
}

// ─────────────────────────────────────────────────────────────────────────────
// LOAD STATS → GET /api/stats/{sessionId}?category=...
// ─────────────────────────────────────────────────────────────────────────────
async function loadStats() {
  if (!state.sessionId) return;
  try {
    const res   = await fetch(`${BASE_URL}/stats/${state.sessionId}?category=${encodeURIComponent(state.category)}`);
    const stats = await res.json();
    renderStats(stats);
  } catch (_) {}
}

// ─────────────────────────────────────────────────────────────────────────────
// PAGINATION
// ─────────────────────────────────────────────────────────────────────────────
function renderPagination(data) {
  if (data.totalPages <= 1) {
    paginationBar.style.display = 'none';
    return;
  }
  paginationBar.style.display = 'flex';
  $('btn-prev').disabled = data.first;
  $('btn-next').disabled = data.last;

  const from = data.currentPage * data.pageSize + 1;
  const to   = Math.min((data.currentPage + 1) * data.pageSize, data.totalElements);
  pageInfo.textContent = `Page ${data.currentPage + 1} of ${data.totalPages}  (${from}–${to} of ${data.totalElements.toLocaleString('en-IN')})`;
}

function initPagination() {
  $('btn-prev').addEventListener('click', () => {
    if (state.currentPage > 0) {
      state.currentPage--;
      loadCutoffs();
      window.scrollTo({ top: resultsSection.offsetTop - 80, behavior: 'smooth' });
    }
  });
  $('btn-next').addEventListener('click', () => {
    if (state.currentPage < state.totalPages - 1) {
      state.currentPage++;
      loadCutoffs();
      window.scrollTo({ top: resultsSection.offsetTop - 80, behavior: 'smooth' });
    }
  });
}

// ─────────────────────────────────────────────────────────────────────────────
// SORT BUTTONS
// ─────────────────────────────────────────────────────────────────────────────
function initSortButtons() {
  const sortBtns = document.querySelectorAll('.btn-sort');
  sortBtns.forEach(btn => {
    btn.addEventListener('click', () => {
      sortBtns.forEach(b => {
        b.classList.remove('active');
        b.setAttribute('aria-pressed', 'false');
      });
      btn.classList.add('active');
      btn.setAttribute('aria-pressed', 'true');
      state.sort        = btn.dataset.sort;
      state.currentPage = 0;
      loadCutoffs();
    });
  });
}

// ─────────────────────────────────────────────────────────────────────────────
// FILTERS (search, branch, category, rank)
// ─────────────────────────────────────────────────────────────────────────────
function initFilters() {
  $('btn-apply-filters').addEventListener('click', applyFilters);
  $('search-input').addEventListener('keydown', e => { if (e.key === 'Enter') applyFilters(); });
  $('gm-input').addEventListener('keydown',     e => { if (e.key === 'Enter') applyFilters(); });
  $('branch-filter').addEventListener('change', applyFilters);

  // Category change: reload stats + results for the new category
  categoryFilter.addEventListener('change', () => {
    state.category    = categoryFilter.value;
    state.currentPage = 0;
    updateCutoffHeader(state.category);
    loadStats();
    loadCutoffs();
  });

  // Show All — clears rank filter
  $('btn-show-all').addEventListener('click', () => {
    $('gm-input').value   = '';
    state.maxRank         = 0;
    state.userRank        = 0;
    state.currentPage     = 0;
    loadCutoffs();
  });
}

function applyFilters() {
  const rankVal       = parseInt($('gm-input').value, 10);
  state.userRank      = isNaN(rankVal) ? 0 : rankVal;
  state.maxRank       = state.userRank;   // show colleges with cutoff >= user rank
  state.search        = $('search-input').value.trim();
  state.branch        = $('branch-filter').value;
  state.currentPage   = 0;
  loadCutoffs();
}

// ─────────────────────────────────────────────────────────────────────────────
// EXPORT — CSV & Excel (category-aware)
// ─────────────────────────────────────────────────────────────────────────────
function initExport() {
  $('btn-export-csv').addEventListener('click',   () => doExport('csv'));
  $('btn-export-excel').addEventListener('click', () => doExport('excel'));
}

async function doExport(format) {
  if (!state.sessionId) {
    alert('Please upload a file first.');
    return;
  }

  const btn      = format === 'csv' ? $('btn-export-csv') : $('btn-export-excel');
  const origText = btn.innerHTML;
  btn.innerHTML  = '<span class="spinner" style="width:14px;height:14px;border-width:2px;"></span> Exporting…';
  btn.disabled   = true;

  const params = new URLSearchParams({
    category: state.category,
    maxRank:  state.maxRank,
    search:   state.search,
    branch:   state.branch,
  });

  try {
    const res  = await fetch(`${BASE_URL}/export/${state.sessionId}?${params}`);
    const data = await res.json();

    if (!res.ok || !Array.isArray(data)) {
      alert('Export failed. Please try again.');
      return;
    }

    if (format === 'csv') {
      exportCSV(data);
    } else {
      exportExcel(data);
    }
  } catch (err) {
    alert('Network error during export.');
    console.error(err);
  } finally {
    btn.innerHTML = origText;
    btn.disabled  = false;
  }
}

function exportCSV(records) {
  const catLabel = state.category;
  const header   = ['Sl.No', 'College Code', 'College Name', 'Branch Name',
                     `Category`, `${catLabel} Cutoff Rank`, 'Eligible (Y/N)'];
  const rows = records.map((r, i) => [
    i + 1,
    r.collegeCode ?? '',
    r.collegeName ?? '',
    r.branchName  ?? '',
    r.category    ?? catLabel,
    r.cutoffRank  ?? '',
    state.userRank > 0 ? (state.userRank <= (r.cutoffRank ?? 0) ? 'Y' : 'N') : '',
  ]);

  const csvContent = [header, ...rows]
    .map(row => row.map(cell => `"${String(cell).replace(/"/g, '""')}"`).join(','))
    .join('\r\n');

  const blob = new Blob(['\uFEFF' + csvContent], { type: 'text/csv;charset=utf-8;' });
  downloadFile(blob, `KCET_${catLabel}_Cutoffs_${state.sessionId}.csv`);
}

function exportExcel(records) {
  if (typeof XLSX === 'undefined') {
    alert('SheetJS library is not loaded. Please check your internet connection.');
    return;
  }

  const catLabel = state.category;
  const wsData   = [
    ['Sl.No', 'College Code', 'College Name', 'Branch Name',
     'Category', `${catLabel} Cutoff Rank`, 'Eligible (Y/N)'],
    ...records.map((r, i) => [
      i + 1,
      r.collegeCode ?? '',
      r.collegeName ?? '',
      r.branchName  ?? '',
      r.category    ?? catLabel,
      r.cutoffRank  ?? '',
      state.userRank > 0 ? (state.userRank <= (r.cutoffRank ?? 0) ? 'Y' : 'N') : '',
    ])
  ];

  const wb = XLSX.utils.book_new();
  const ws = XLSX.utils.aoa_to_sheet(wsData);

  ws['!cols'] = [
    { wch: 6  },  // Sl.No
    { wch: 12 },  // Code
    { wch: 50 },  // College Name
    { wch: 55 },  // Branch
    { wch: 10 },  // Category
    { wch: 20 },  // Cutoff
    { wch: 14 },  // Eligible
  ];

  XLSX.utils.book_append_sheet(wb, ws, `KCET ${catLabel} Cutoffs`);
  XLSX.writeFile(wb, `KCET_${catLabel}_Cutoffs_${state.sessionId}.xlsx`);
}

function downloadFile(blob, filename) {
  const url  = URL.createObjectURL(blob);
  const link = document.createElement('a');
  link.href     = url;
  link.download = filename;
  document.body.appendChild(link);
  link.click();
  document.body.removeChild(link);
  setTimeout(() => URL.revokeObjectURL(url), 2000);
}

// ─────────────────────────────────────────────────────────────────────────────
// SESSION SELECTOR
// ─────────────────────────────────────────────────────────────────────────────
function initSessionSelector() {
  sessionSelect.addEventListener('change', async () => {
    const sid = sessionSelect.value;
    if (!sid) return;

    state.sessionId   = sid;
    state.currentPage = 0;
    state.search      = '';
    state.branch      = '';
    state.maxRank     = 0;
    state.userRank    = 0;
    state.category    = 'GM';   // reset to GM when switching sessions

    // Reset filter inputs
    $('search-input').value   = '';
    $('branch-filter').value  = '';
    $('gm-input').value       = '';
    if (categoryFilter) categoryFilter.disabled = true;

    // Load categories for new session
    await loadCategories();

    // Load stats for GM
    await loadStats();

    // Load results
    await loadCutoffs();
  });

  $('btn-delete-session').addEventListener('click', async () => {
    const sid = sessionSelect.value;
    if (!sid) return;
    if (!confirm(`Delete all data for session "${sid}"? This cannot be undone.`)) return;

    try {
      await fetch(`${BASE_URL}/sessions/${sid}`, { method: 'DELETE' });
      const opt = sessionSelect.querySelector(`option[value="${sid}"]`);
      if (opt) opt.remove();

      if (sessionSelect.options.length <= 1) {
        sessionWrap.style.display    = 'none';
        statsSection.style.display   = 'none';
        resultsSection.style.display = 'none';
        state.sessionId = null;
        categoryFilter.innerHTML = '<option value="GM">GM — General Merit</option>';
        categoryFilter.disabled  = true;
      } else {
        sessionSelect.selectedIndex = 1;
        sessionSelect.dispatchEvent(new Event('change'));
      }
    } catch (err) {
      alert('Failed to delete session.');
    }
  });
}

async function loadExistingSessions() {
  try {
    const res      = await fetch(`${BASE_URL}/sessions`);
    const sessions = await res.json();

    if (Array.isArray(sessions) && sessions.length > 0) {
      sessions.forEach(sid => addSessionOption(sid, sid, ''));
      sessionWrap.style.display = 'flex';

      state.sessionId = sessions[0];
      sessionSelect.value = sessions[0];

      // Load categories first, then stats + results
      await loadCategories();
      await loadStats();
      await loadCutoffs();
    }
  } catch (_) {
    // Backend not running — silently ignore on initial load
  }
}

function addSessionOption(sessionId, label, count) {
  if (sessionSelect.querySelector(`option[value="${sessionId}"]`)) return;
  const opt   = document.createElement('option');
  opt.value   = sessionId;
  opt.text    = count ? `Session: ${sessionId} (${count} records)` : `Session: ${sessionId}`;
  sessionSelect.appendChild(opt);
}

// ─────────────────────────────────────────────────────────────────────────────
// UPLOAD MESSAGE HELPERS
// ─────────────────────────────────────────────────────────────────────────────
function showUploadMessage(msg, type) {
  uploadMessage.textContent = msg;
  uploadMessage.className   = `upload-message ${type}`;
}

function clearUploadMessage() {
  uploadMessage.textContent = '';
  uploadMessage.className   = 'upload-message';
}

// ─────────────────────────────────────────────────────────────────────────────
// UTILITIES
// ─────────────────────────────────────────────────────────────────────────────

function escHtml(str) {
  const div = document.createElement('div');
  div.textContent = str;
  return div.innerHTML;
}

/** Shorten long branch names for display in the pill badge.
 *  Uses pattern matching — works for ALL branches, no hardcoded list needed. */
function shortBranch(branch) {
  const b = branch.toLowerCase();

  if (b.includes('artificial intelligence and machine learning') && b.includes('computer science')) return 'CSE (AI & ML)';
  if (b.includes('artificial intelligence and data science') && b.includes('computer science'))    return 'CSE (AI & DS)';
  if (b.includes('data science') && b.includes('computer science'))    return 'CSE (Data Sci)';
  if (b.includes('cyber security') && b.includes('computer science'))  return 'CSE (Cyber Sec)';
  if (b.includes('internet of things') && b.includes('computer science')) return 'CSE (IoT)';
  if (b.includes('computer science'))  return 'CSE';

  if (b.includes('artificial intelligence and machine learning')) return 'AI & ML';
  if (b.includes('artificial intelligence and data science'))     return 'AI & Data Sci';
  if (b.includes('artificial intelligence'))                      return 'AI';

  if (b.includes('electronics and telecommunication'))  return 'E & TC';
  if (b.includes('electronics and instrumentation'))    return 'E & I';
  if (b.includes('electronics and communication'))      return 'ECE';
  if (b.includes('electrical & electronics') || b.includes('electrical and electronics')) return 'EEE';
  if (b.includes('electrical'))                         return 'EE';

  if (b.includes('mechanical'))       return 'Mechanical';
  if (b.includes('civil'))            return 'Civil';
  if (b.includes('chemical'))         return 'Chemical';
  if (b.includes('information science')) return 'ISE';
  if (b.includes('industrial'))       return 'Industrial';
  if (b.includes('aerospace') || b.includes('aero space')) return 'Aerospace';
  if (b.includes('bio-technology') || b.includes('biotechnology')) return 'Bio-Tech';
  if (b.includes('silk technology'))  return 'Silk Tech';
  if (b.includes('textiles'))         return 'Textiles';
  if (b.includes('architecture'))     return 'Arch';
  if (b.includes('mining'))           return 'Mining';

  return branch.length > 28 ? branch.substring(0, 25) + '\u2026' : branch;
}

/** Animate stat number counters */
function animateNumbers() {
  const els = document.querySelectorAll('.stat-value');
  els.forEach(el => {
    const raw   = el.textContent.replace(/[,\s]/g, '');
    const final = parseInt(raw, 10);
    if (isNaN(final) || final === 0) return;

    let start   = 0;
    const steps = 25;
    const step  = Math.ceil(final / steps);
    const timer = setInterval(() => {
      start = Math.min(start + step, final);
      el.textContent = start.toLocaleString('en-IN');
      if (start >= final) clearInterval(timer);
    }, 30);
  });
}
