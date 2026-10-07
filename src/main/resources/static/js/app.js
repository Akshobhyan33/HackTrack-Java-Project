const API = '/api';
let allHackathons = [];
let selectedHackathonId = null;

// ── Initialization ──
document.addEventListener('DOMContentLoaded', async () => {
    const user = await loadCurrentUser();
    if (!user) return; // redirect to the login page already triggered
    loadHackathons();
    loadHistory();
    loadGmailStatus();
    checkGmailCallback();
});

// ── Authentication ──
async function loadCurrentUser() {
    try {
        const res = await fetch('/api/auth/me');
        if (res.status === 401) {
            window.location.href = '/login.html';
            return null;
        }
        if (!res.ok) return null;
        const user = await res.json();
        const chip = document.getElementById('userChip');
        if (chip) chip.textContent = user.name + ' · ' + user.email;
        return user;
    } catch (e) {
        console.error('Failed to load current user:', e);
        return null;
    }
}

async function logout() {
    try {
        await fetch('/api/auth/logout', { method: 'POST' });
    } catch (e) {
        // Session may already be gone; go to the login page either way.
    }
    window.location.href = '/login.html';
}

// ── API helpers ──
async function apiFetch(url, options = {}) {
    const res = await fetch(API + url, {
        headers: { 'Content-Type': 'application/json' },
        ...options
    });
    if (res.status === 401) {
        window.location.href = '/login.html';
        throw new Error('Not signed in.');
    }
    if (!res.ok) {
        const text = await res.text();
        throw new Error(`API error ${res.status}: ${text}`);
    }
    if (res.status === 204 || options.method === 'DELETE') {
        if (res.headers.get('content-type')?.includes('json')) return res.json();
        return null;
    }
    // Some endpoints answer 200 with no body at all (a hackathon without a next
    // stage returns an empty response). Parsing that would throw, which used to
    // abort loadStages before it could render and left the previously selected
    // hackathon's stages on screen.
    const text = await res.text();
    if (!text || !text.trim()) return null;
    return JSON.parse(text);
}

// AI endpoints answer with { error, code }; surface that message directly.
async function aiApiFetch(url, options = {}) {
    const res = await fetch(API + url, {
        headers: { 'Content-Type': 'application/json' },
        ...options
    });
    if (res.status === 401) {
        window.location.href = '/login.html';
        throw new Error('Not signed in.');
    }
    let data = null;
    try {
        data = await res.json();
    } catch (e) {
        data = null;
    }
    if (!res.ok) {
        const err = new Error((data && data.error) || `Request failed (${res.status}).`);
        err.code = data ? data.code : null;
        throw err;
    }
    return data;
}

// ── Hackathons ──
async function loadHackathons() {
    try {
        allHackathons = await apiFetch('/hackathons');
        renderHackathons(allHackathons);
        updateStatusBar();
        if (selectedHackathonId) {
            await loadStages(selectedHackathonId);
        }
    } catch (e) {
        console.error('Failed to load hackathons:', e);
    }
}

function renderHackathons(hackathons) {
    const container = document.getElementById('hackathonList');
    if (hackathons.length === 0) {
        container.innerHTML = `
            <div class="empty-state">
                <div class="empty-icon">&#127942;</div>
                <p><strong>No hackathons yet</strong></p>
                <p>Click "Add Hackathon" to start tracking your hackathon journey.</p>
            </div>`;
        return;
    }
    container.innerHTML = hackathons.map(h => `
        <div class="hackathon-card ${selectedHackathonId === h.id ? 'selected' : ''}"
             onclick="selectHackathon(${h.id})" data-id="${h.id}">
            <button class="star-btn ${h.starred ? 'active' : ''}"
                    onclick="event.stopPropagation(); toggleStar(${h.id})"
                    title="${h.starred ? 'Unstar' : 'Star'} this hackathon">
                ${h.starred ? '&#9733;' : '&#9734;'}
            </button>
            <div class="info">
                <div class="name">${escapeHtml(h.name)}</div>
                ${h.websiteUrl ? `<div class="url">${escapeHtml(h.websiteUrl)}</div>` : ''}
            </div>
            <div class="meta">
                <div class="stages-count">${h.stageCount} stage${h.stageCount !== 1 ? 's' : ''}</div>
                <span class="status-badge status-${h.statusString}">${formatStatus(h.statusString)}</span>
            </div>
        </div>
    `).join('');
}

function formatStatus(s) {
    const map = { 'NO_STAGES': 'No Stages', 'ACTIVE': 'Active', 'ELIMINATED': 'Eliminated', 'COMPLETED': 'Completed' };
    return map[s] || s;
}

function filterHackathons() {
    const query = document.getElementById('searchField').value.toLowerCase();
    const filtered = allHackathons.filter(h => h.name.toLowerCase().includes(query));
    renderHackathons(filtered);
}

function updateStatusBar() {
    const total = allHackathons.length;
    const active = allHackathons.filter(h => h.statusString === 'ACTIVE').length;
    const eliminated = allHackathons.filter(h => h.statusString === 'ELIMINATED').length;
    const completed = allHackathons.filter(h => h.statusString === 'COMPLETED').length;
    document.getElementById('statusBar').textContent =
        `${total} hackathon${total !== 1 ? 's' : ''} \u2022 ${active} active \u2022 ${eliminated} eliminated \u2022 ${completed} completed`;
}

async function selectHackathon(id) {
    selectedHackathonId = id;
    // Switching hackathons must never carry another one's AI preview or details.
    resetHackathonDetailState();
    const hackathon = allHackathons.find(h => h.id === id);
    document.getElementById('selectedHackathonName').textContent = hackathon ? hackathon.name : 'Hackathon';
    document.getElementById('selectedHackathonUrl').textContent = hackathon?.websiteUrl || '';
    document.getElementById('stagePanel').classList.remove('hidden');

    document.querySelectorAll('.hackathon-card').forEach(card => {
        card.classList.toggle('selected', parseInt(card.dataset.id) === id);
    });

    await loadStages(id);
}

async function loadStages(hackathonId) {
    // Clear whatever the previously selected hackathon was showing before asking
    // for anything. If any request fails from here on, the panel stays empty
    // instead of silently keeping the other hackathon's stages and status.
    renderStages([]);
    renderNextStage(null);
    document.getElementById('overallStatus').innerHTML = '';

    let stages = [];
    try {
        const loaded = await apiFetch(`/hackathons/${hackathonId}/stages`);
        stages = Array.isArray(loaded) ? loaded : [];
    } catch (e) {
        console.error('Failed to load stages:', e);
    }
    renderStages(stages);

    // Status and next-stage are extras: a failure here must not hide the stages.
    try {
        const overall = await apiFetch(`/hackathons/${hackathonId}/overall-status`);
        if (overall && overall.status) renderOverallStatus(overall.status);
    } catch (e) {
        console.error('Failed to load overall status:', e);
    }
    try {
        renderNextStage(await apiFetch(`/hackathons/${hackathonId}/next-stage`));
    } catch (e) {
        console.error('Failed to load next stage:', e);
    }
}

function renderOverallStatus(status) {
    const el = document.getElementById('overallStatus');
    el.innerHTML = `Overall: <span class="status-badge status-${status}">${formatStatus(status)}</span>`;
}

function renderNextStage(stage) {
    const el = document.getElementById('nextStage');
    if (!stage) {
        el.innerHTML = '';
        return;
    }
    el.innerHTML = `<strong>Next up:</strong> ${escapeHtml(stage.name)} &mdash; Deadline: ${stage.deadline} &mdash; ${stage.status}`;
}

function renderStages(stages) {
    const container = document.getElementById('stageList');
    if (stages.length === 0) {
        container.innerHTML = `
            <div class="empty-state">
                <p><strong>No stages yet</strong></p>
                <p>Add stages like Registration, PPT Submission, Rounds, etc.</p>
            </div>`;
        return;
    }
    container.innerHTML = stages.map(s => `
        <div class="stage-card stage-${s.statusEnum}">
            <div class="stage-order">#${s.stageOrder}</div>
            <div class="stage-info">
                <div class="stage-name">${escapeHtml(s.name)}</div>
                <div class="stage-deadline">Deadline: ${formatDate(s.deadline)}</div>
            </div>
            <span class="stage-status status-${s.statusEnum}">${s.status}</span>
            <div class="stage-actions-btns">
                <button onclick="showEditStage(${s.id})">Edit</button>
                <button class="danger" onclick="deleteStage(${s.id})">Delete</button>
            </div>
        </div>
    `).join('');
}

function formatDate(dateStr) {
    if (!dateStr) return 'N/A';
    const d = new Date(dateStr + 'T00:00:00');
    return d.toLocaleDateString('en-US', { year: 'numeric', month: 'short', day: 'numeric' });
}

// ── Add Hackathon ──
function showAddHackathon() {
    // A new hackathon must not inherit anything from the one on screen:
    // no stages, no AI preview, no cached stage details.
    resetHackathonDetailState();
    document.getElementById('modalTitle').textContent = 'Add Hackathon';
    document.getElementById('modalBody').innerHTML = `
        <label for="hackName">Hackathon Name *</label>
        <input type="text" id="hackName" placeholder="e.g., Smart India Hackathon 2026">
        <label for="hackUrl">Website URL</label>
        <input type="url" id="hackUrl" placeholder="https://example.com">
        <div class="form-actions">
            <button class="btn btn-secondary" onclick="closeModal()">Cancel</button>
            <button class="btn btn-primary" onclick="submitAddHackathon()">Add Hackathon</button>
        </div>
    `;
    document.getElementById('modal').classList.remove('hidden');
    document.getElementById('hackName').focus();
}

async function submitAddHackathon() {
    const name = document.getElementById('hackName').value.trim();
    const url = document.getElementById('hackUrl').value.trim();
    if (!name) { alert('Name is required.'); return; }

    try {
        await apiFetch('/hackathons', {
            method: 'POST',
            body: JSON.stringify({ name, websiteUrl: url })
        });
        closeModal();
        await loadHackathons();
    } catch (e) {
        alert('Error adding hackathon: ' + e.message);
    }
}

// ── Toggle Star ──
async function toggleStar(id) {
    try {
        await apiFetch(`/hackathons/${id}/toggle-star`, { method: 'PUT' });
        await loadHackathons();
    } catch (e) {
        console.error('Toggle star failed:', e);
    }
}

// ── Delete Hackathon ──
async function deleteHackathon() {
    if (!selectedHackathonId) return;
    const hackathon = allHackathons.find(h => h.id === selectedHackathonId);
    if (!confirm(`Delete "${hackathon.name}" and all its stages? This cannot be undone.`)) return;

    try {
        await apiFetch(`/hackathons/${selectedHackathonId}`, { method: 'DELETE' });
        selectedHackathonId = null;
        document.getElementById('stagePanel').classList.add('hidden');
        await loadHackathons();
        await loadHistory();
    } catch (e) {
        alert('Error deleting hackathon: ' + e.message);
    }
}

// ── Add Stage ──
function showAddStage() {
    if (!selectedHackathonId) { alert('Select a hackathon first.'); return; }
    document.getElementById('modalTitle').textContent = 'Add Stage';
    document.getElementById('modalBody').innerHTML = `
        <label for="stageName">Stage Name *</label>
        <input type="text" id="stageName" placeholder="e.g., PPT Submission, Round 1, Finals">
        <label for="stageDeadline">Deadline *</label>
        <input type="date" id="stageDeadline">
        <div class="form-actions">
            <button class="btn btn-secondary" onclick="closeModal()">Cancel</button>
            <button class="btn btn-primary" onclick="submitAddStage()">Add Stage</button>
        </div>
    `;
    document.getElementById('modal').classList.remove('hidden');
    document.getElementById('stageName').focus();
}

async function submitAddStage() {
    const name = document.getElementById('stageName').value.trim();
    const deadline = document.getElementById('stageDeadline').value;
    if (!name || !deadline) { alert('Name and deadline are required.'); return; }

    try {
        await apiFetch(`/hackathons/${selectedHackathonId}/stages`, {
            method: 'POST',
            body: JSON.stringify({ name, deadline })
        });
        closeModal();
        await loadHackathons();
    } catch (e) {
        alert('Error adding stage: ' + e.message);
    }
}

// ── Edit Stage ──
async function showEditStage(stageId) {
    try {
        const stages = await apiFetch(`/hackathons/${selectedHackathonId}/stages`);
        const stage = stages.find(s => s.id === stageId);
        if (!stage) return;

        document.getElementById('modalTitle').textContent = 'Edit Stage';
        document.getElementById('modalBody').innerHTML = `
            <label for="editStageName">Stage Name *</label>
            <input type="text" id="editStageName" value="${escapeHtml(stage.name)}">
            <label for="editStageDeadline">Deadline *</label>
            <input type="date" id="editStageDeadline" value="${stage.deadline}">
            <label for="editStageStatus">Status</label>
            <select id="editStageStatus">
                <option value="UPCOMING" ${stage.dbStatus === 'UPCOMING' ? 'selected' : ''}>Upcoming</option>
                <option value="SUBMITTED" ${stage.dbStatus === 'SUBMITTED' ? 'selected' : ''}>Submitted</option>
                <option value="QUALIFIED" ${stage.dbStatus === 'QUALIFIED' ? 'selected' : ''}>Qualified</option>
                <option value="ELIMINATED" ${stage.dbStatus === 'ELIMINATED' ? 'selected' : ''}>Eliminated</option>
                <option value="COMPLETED" ${stage.dbStatus === 'COMPLETED' ? 'selected' : ''}>Completed</option>
                <option value="NOT_APPLICABLE" ${stage.dbStatus === 'NOT_APPLICABLE' ? 'selected' : ''}>Not Applicable</option>
            </select>
            <div class="form-actions">
                <button class="btn btn-secondary" onclick="closeModal()">Cancel</button>
                <button class="btn btn-primary" onclick="submitEditStage(${stageId})">Save Changes</button>
            </div>
        `;
        document.getElementById('modal').classList.remove('hidden');
    } catch (e) {
        alert('Error loading stage: ' + e.message);
    }
}

async function submitEditStage(stageId) {
    const name = document.getElementById('editStageName').value.trim();
    const deadline = document.getElementById('editStageDeadline').value;
    const status = document.getElementById('editStageStatus').value;
    if (!name || !deadline) { alert('Name and deadline are required.'); return; }

    try {
        await apiFetch(`/stages/${stageId}`, {
            method: 'PUT',
            body: JSON.stringify({ name, deadline, status })
        });
        closeModal();
        await loadHackathons();
    } catch (e) {
        alert('Error updating stage: ' + e.message);
    }
}

// ── Delete Stage ──
async function deleteStage(stageId) {
    if (!confirm('Delete this stage?')) return;
    try {
        await apiFetch(`/stages/${stageId}`, { method: 'DELETE' });
        await loadHackathons();
    } catch (e) {
        alert('Error deleting stage: ' + e.message);
    }
}

// ── AI Schedule Extraction ──
let aiPreview = null;

// Drops any preview/details left over from another hackathon so a NEW hackathon
// always starts clean: stages = [], AI preview cleared.
function resetHackathonDetailState() {
    aiPreview = null;
}

async function showAiScheduleModal() {
    if (!selectedHackathonId) { alert('Select a hackathon first.'); return; }
    const hackathon = allHackathons.find(h => h.id === selectedHackathonId);
    aiPreview = null;

    document.getElementById('modalTitle').textContent = 'AI Schedule Extraction';
    document.getElementById('modalBody').innerHTML = `
        <p class="ai-subtitle">
            HackTrack reads the official hackathon website and asks the AI to pull out
            stages and deadlines. Nothing is saved until you review and apply the results.
        </p>
        <label for="aiName">Hackathon Name</label>
        <input type="text" id="aiName" placeholder="e.g., Smart India Hackathon">
        <label for="aiUrl">Official Hackathon URL</label>
        <input type="url" id="aiUrl" placeholder="https://www.example.com">
        <div class="form-actions">
            <button class="btn btn-secondary" onclick="closeModal()">Cancel</button>
            <button class="btn btn-primary" id="aiFetchBtn" onclick="fetchAiSchedule()">Fetch Schedule with AI</button>
        </div>
        <div id="aiMessage" class="ai-message hidden"></div>
        <div id="aiResults" class="ai-results hidden"></div>
    `;
    document.getElementById('modalBody').parentElement.classList.add('modal-wide');
    document.getElementById('modal').classList.remove('hidden');
    document.getElementById('aiName').value = hackathon ? hackathon.name : '';
    document.getElementById('aiUrl').value = hackathon ? (hackathon.websiteUrl || '') : '';
    checkAiStatus();
}

async function checkAiStatus() {
    try {
        const status = await aiApiFetch('/ai/status');
        if (!status.configured) {
            showAiMessage(status.message || 'AI extraction is not configured yet.', 'error');
            document.getElementById('aiFetchBtn').disabled = true;
        }
    } catch (e) {
        // Status check is advisory only - the real request will report failures.
    }
}

async function fetchAiSchedule() {
    if (!selectedHackathonId) { alert('Select a hackathon first.'); return; }
    const name = document.getElementById('aiName').value.trim();
    const url = document.getElementById('aiUrl').value.trim();
    if (!url) { showAiMessage('Enter the official hackathon URL first.', 'error'); return; }

    const btn = document.getElementById('aiFetchBtn');
    btn.disabled = true;
    btn.textContent = 'Analysing website...';
    document.getElementById('aiResults').classList.add('hidden');
    showAiMessage('Fetching the website and analysing it with AI. This can take a moment.', 'info');

    try {
        const preview = await aiApiFetch(`/hackathons/${selectedHackathonId}/ai-schedule`, {
            method: 'POST',
            body: JSON.stringify({ name, url })
        });
        aiPreview = preview;
        hideAiMessage();
        renderAiResults(preview);
    } catch (e) {
        aiPreview = null;
        document.getElementById('aiResults').classList.add('hidden');
        showAiMessage(e.message, 'error');
    } finally {
        btn.disabled = false;
        btn.textContent = 'Fetch Schedule with AI';
    }
}

function renderAiResults(preview) {
    const results = document.getElementById('aiResults');
    const rows = preview.stages.map((s, i) => {
        const label = s.canonicalName || s.reportedName;
        const tag = aiActionTag(s);
        const change = aiChangeNote(s);
        const disabled = s.action === 'SKIP';
        const note = disabled ? escapeHtml(s.skipReason || 'Cannot be applied') : change;
        return `
            <tr class="${disabled ? 'ai-row-skipped' : ''}">
                <td class="ai-check-cell">
                    <input type="checkbox" class="ai-pick" data-index="${i}"
                           ${disabled ? 'disabled' : ''} ${s.selected ? 'checked' : ''}>
                </td>
                <td><strong>${escapeHtml(label)}</strong>${s.recognized ? '' : '<br><span class="ai-change-note">Custom stage</span>'}</td>
                <td class="ai-date">${s.date ? escapeHtml(formatDate(s.date)) : 'Unknown'}</td>
                <td class="ai-desc">${escapeHtml(s.description || '')}${note ? `<span class="ai-change-note">${note}</span>` : ''}</td>
                <td>${tag}</td>
            </tr>`;
    }).join('');

    const source = (preview.pagesRead || []).map(u => escapeHtml(u)).join('<br>');
    const notes = (preview.notes || []).length
        ? `<p class="ai-notes">${preview.notes.map(escapeHtml).join('<br>')}</p>`
        : '';

    results.innerHTML = `
        <h4>Detected Stages</h4>
        <p class="ai-source">Read from:<br>${source}</p>
        ${notes}
        <table class="ai-table">
            <thead>
                <tr><th></th><th>Stage</th><th>Date</th><th>Description</th><th>Action</th></tr>
            </thead>
            <tbody>${rows}</tbody>
        </table>
        <label class="ai-check">
            <input type="checkbox" id="aiOverwrite">
            Replace dates on stages I already added manually
        </label>
        <label class="ai-check">
            <input type="checkbox" id="aiAllowCustom">
            Also add stages that are not part of the standard HackTrack stages
        </label>
        <p class="ai-summary" id="aiSummary">${aiSummaryText(preview.stages)}</p>
        <div class="form-actions">
            <button class="btn btn-secondary" onclick="closeModal()">Cancel</button>
            <button class="btn btn-primary" onclick="applyAiSchedule()">Apply Schedule</button>
        </div>
    `;
    results.classList.remove('hidden');

    results.querySelectorAll('.ai-pick').forEach(box => {
        box.addEventListener('change', updateAiSummary);
    });
    const overwrite = document.getElementById('aiOverwrite');
    overwrite.addEventListener('change', () => {
        results.querySelectorAll('.ai-pick').forEach(box => {
            const stage = aiPreview.stages[Number(box.dataset.index)];
            if (box.disabled) return;
            if (stage.action === 'UPDATE') {
                box.checked = overwrite.checked;
            }
        });
        updateAiSummary();
    });
    const allowCustom = document.getElementById('aiAllowCustom');
    allowCustom.addEventListener('change', () => {
        results.querySelectorAll('.ai-pick').forEach(box => {
            const stage = aiPreview.stages[Number(box.dataset.index)];
            if (!stage.recognized && stage.date) {
                box.disabled = !allowCustom.checked;
                if (allowCustom.checked) box.checked = true;
            }
        });
        updateAiSummary();
    });
}

function aiActionTag(stage) {
    if (!stage.recognized && stage.date) {
        return '<span class="ai-tag ai-tag-skip">Custom</span>';
    }
    switch (stage.action) {
        case 'CREATE': return '<span class="ai-tag ai-tag-new">New</span>';
        case 'UPDATE': return '<span class="ai-tag ai-tag-change">Replace</span>';
        case 'UNCHANGED': return '<span class="ai-tag ai-tag-same">Same</span>';
        default: return '<span class="ai-tag ai-tag-skip">Skipped</span>';
    }
}

function aiChangeNote(stage) {
    if (stage.action === 'UPDATE') {
        return `Current date: ${stage.existingDeadline ? formatDate(stage.existingDeadline) : 'none'} &rarr; new date: ${formatDate(stage.date)}`;
    }
    if (stage.action === 'CREATE' && stage.existingStageName) {
        return `Existing stage "${stage.existingStageName}" is left untouched.`;
    }
    return '';
}

function aiSummaryText(stages) {
    const newCount = stages.filter(s => s.action === 'CREATE').length;
    const changeCount = stages.filter(s => s.action === 'UPDATE').length;
    const sameCount = stages.filter(s => s.action === 'UNCHANGED').length;
    const skipCount = stages.filter(s => s.action === 'SKIP').length;
    const selected = stages.filter(s => s.selected && s.action !== 'SKIP').length;
    return `${selected} stage${selected !== 1 ? 's' : ''} selected to apply `
        + `(${newCount} new, ${changeCount} to replace, ${sameCount} already correct, ${skipCount} skipped). `
        + `Existing stage statuses and manual entries are never removed.`;
}

function updateAiSummary() {
    const el = document.getElementById('aiSummary');
    if (!el || !aiPreview) return;
    el.textContent = aiSummaryText(aiPreview.stages.map((s, i) => {
        const box = document.querySelector(`.ai-pick[data-index="${i}"]`);
        return {
            action: s.action,
            selected: !!(box && !box.disabled && box.checked)
        };
    }));
}

async function applyAiSchedule() {
    if (!aiPreview) { showAiMessage('Fetch the schedule first.', 'error'); return; }
    const overwrite = document.getElementById('aiOverwrite').checked;
    const allowCustom = document.getElementById('aiAllowCustom').checked;

    const stages = aiPreview.stages.filter((s, i) => {
        const box = document.querySelector(`.ai-pick[data-index="${i}"]`);
        return box && !box.disabled && box.checked;
    }).map(s => ({
        stageName: s.canonicalName || s.reportedName,
        date: s.date,
        description: s.description
    }));

    if (stages.length === 0) {
        showAiMessage('Select at least one stage to apply.', 'error');
        return;
    }

    try {
        const result = await aiApiFetch(`/hackathons/${selectedHackathonId}/ai-schedule/apply`, {
            method: 'POST',
            body: JSON.stringify({
                name: document.getElementById('aiName').value.trim(),
                url: document.getElementById('aiUrl').value.trim(),
                overwriteExisting: overwrite,
                allowCustomStages: allowCustom,
                stages
            })
        });
        renderAiApplyResult(result);
        await loadHackathons();
    } catch (e) {
        showAiMessage(e.message, 'error');
    }
}

function renderAiApplyResult(result) {
    const results = document.getElementById('aiResults');
    const outcomes = (result.outcomes || []).map(o => {
        const tag = o.action === 'CREATE' ? 'ai-tag-new'
            : o.action === 'UPDATE' ? 'ai-tag-change'
                : o.action === 'UNCHANGED' ? 'ai-tag-same' : 'ai-tag-skip';
        return `<li>${escapeHtml(o.stageName || '')} &mdash; <span class="ai-tag ${tag}">${o.action}</span> ${escapeHtml(o.message || '')}</li>`;
    }).join('');

    const warnings = (result.warnings || []).length
        ? `<p class="ai-notes">${result.warnings.map(escapeHtml).join('<br>')}</p>`
        : '';

    results.innerHTML = `
        <h4>Schedule Applied</h4>
        <p class="ai-summary">${result.created} new, ${result.updated} updated, ${result.unchanged} unchanged,
            ${result.skipped} skipped. Stage tracking and reminders now use these dates.</p>
        ${warnings}
        <ul class="ai-outcomes">${outcomes}</ul>
        <div class="form-actions">
            <button class="btn btn-primary" onclick="closeModal()">Done</button>
        </div>
    `;
    results.classList.remove('hidden');
    hideAiMessage();
}

function showAiMessage(msg, type) {
    const el = document.getElementById('aiMessage');
    if (!el) { alert(msg); return; }
    el.textContent = msg;
    el.className = 'ai-message ' + type;
    el.classList.remove('hidden');
}

function hideAiMessage() {
    const el = document.getElementById('aiMessage');
    if (el) el.classList.add('hidden');
}

// ── History ──
async function loadHistory() {

    try {
        const history = await apiFetch('/history');
        renderHistory(history);
    } catch (e) {
        console.error('Failed to load history:', e);
    }
}

function renderHistory(history) {
    const container = document.getElementById('historyList');
    if (history.length === 0) {
        container.innerHTML = `
            <div class="empty-state">
                <p>No participation history yet.</p>
            </div>`;
        return;
    }
    container.innerHTML = history.map(h => `
        <div class="history-card">
            <div class="hist-info">
                <div class="hist-name">${escapeHtml(h.hackathonName)}</div>
                <div class="hist-date">${h.completedAt ? formatDate(h.completedAt) : 'N/A'}</div>
            </div>
            <span class="status-badge status-${h.finalOutcome === 'WINNER' ? 'COMPLETED' : 'ELIMINATED'}">${h.finalOutcome}</span>
        </div>
    `).join('');
}

// ── Modal ──
function closeModal(event) {
    if (event && event.target !== event.currentTarget) return;
    document.getElementById('modal').classList.add('hidden');
    document.querySelector('.modal-content').classList.remove('modal-wide');
}

document.addEventListener('keydown', (e) => {
    if (e.key === 'Escape') closeModal();
});

// ── Helpers ──
function escapeHtml(text) {
    if (!text) return '';
    const div = document.createElement('div');
    div.textContent = text;
    return div.innerHTML;
}

// ── Gmail Functions ──
function checkGmailCallback() {
    const params = new URLSearchParams(window.location.search);
    const gmailStatus = params.get('gmail');
    if (gmailStatus) {
        if (gmailStatus === 'connected') {
            showGmailMessage('Gmail connected successfully!', 'success');
        } else if (gmailStatus === 'denied') {
            showGmailMessage('Gmail authorization was denied.', 'error');
        } else if (gmailStatus === 'error') {
            showGmailMessage('Gmail connection failed. Please try again.', 'error');
        }
        window.history.replaceState({}, '', window.location.pathname);
    }
}

async function loadGmailStatus() {
    try {
        const status = await apiFetch('/gmail/status');
        const indicator = document.getElementById('gmailIndicator');
        const statusText = document.getElementById('gmailStatusText');
        const connectBtn = document.getElementById('gmailConnectBtn');
        const disconnectBtn = document.getElementById('gmailDisconnectBtn');
        const recipientInput = document.getElementById('recipientEmailInput');
        const lastReminder = document.getElementById('gmailLastReminder');

        if (status.connected) {
            indicator.className = 'gmail-indicator connected';
            statusText.textContent = 'Connected as ' + (status.email || 'unknown');
            connectBtn.classList.add('hidden');
            disconnectBtn.classList.remove('hidden');
        } else {
            indicator.className = 'gmail-indicator disconnected';
            statusText.textContent = 'Not connected';
            connectBtn.classList.remove('hidden');
            disconnectBtn.classList.add('hidden');
        }

        if (status.recipientEmail) {
            recipientInput.value = status.recipientEmail;
        }

        if (status.lastReminder) {
            lastReminder.textContent = status.lastReminder;
        }
    } catch (e) {
        console.error('Failed to load Gmail status:', e);
    }
}

function connectGmail() {
    window.location.href = '/api/gmail/connect';
}

async function disconnectGmail() {
    try {
        await apiFetch('/gmail/disconnect', { method: 'POST' });
        await loadGmailStatus();
        showGmailMessage('Gmail disconnected.', 'info');
    } catch (e) {
        showGmailMessage('Error disconnecting Gmail: ' + e.message, 'error');
    }
}

async function saveRecipientEmail() {
    const email = document.getElementById('recipientEmailInput').value.trim();
    if (!email) {
        showGmailMessage('Please enter an email address.', 'error');
        return;
    }
    if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email)) {
        showGmailMessage('Please enter a valid email address.', 'error');
        return;
    }
    try {
        await apiFetch('/gmail/recipient', {
            method: 'POST',
            body: JSON.stringify({ email })
        });
        showGmailMessage('Recipient email saved.', 'success');
    } catch (e) {
        showGmailMessage('Error saving email: ' + e.message, 'error');
    }
}

async function sendTestEmail() {
    try {
        const result = await apiFetch('/gmail/test', { method: 'POST' });
        if (result.success) {
            showGmailMessage(result.message, 'success');
        } else {
            showGmailMessage(result.message, 'error');
        }
    } catch (e) {
        showGmailMessage('Error sending test email: ' + e.message, 'error');
    }
}

function showGmailMessage(msg, type) {
    const el = document.getElementById('gmailMessage');
    el.textContent = msg;
    el.className = 'gmail-message ' + type;
    el.classList.remove('hidden');
    setTimeout(() => {
        el.classList.add('hidden');
    }, 5000);
}
