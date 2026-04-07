// ── Constants ─────────────────────────────────────────────────────────────
const DEVICE_NAME_KEY = 'soundtag_device_name';
const APP_VERSION     = 'web-1.0.0';
const PREFERRED_MIME  = (() => {
  const candidates = [
    'audio/webm;codecs=opus',
    'audio/webm',
    'audio/ogg;codecs=opus',
    'audio/mp4',
  ];
  return candidates.find(t => MediaRecorder.isTypeSupported(t)) ?? '';
})();
const FILE_EXT = PREFERRED_MIME.startsWith('audio/mp4') ? 'm4a'
               : PREFERRED_MIME.startsWith('audio/ogg') ? 'ogg'
               : 'webm';

// ── State ─────────────────────────────────────────────────────────────────
let mediaRecorder  = null;
let audioChunks    = [];
let timerInterval  = null;
let startTime      = null;   // Date
let capturedCoords = null;   // { latitude, longitude, accuracy }

// ── DOM refs ──────────────────────────────────────────────────────────────
const recordBtn      = document.getElementById('record-btn');
const recordRing     = document.getElementById('record-ring');
const recordIcon     = document.getElementById('record-icon');
const recordLabel    = document.getElementById('record-label');
const timerEl        = document.getElementById('timer');
const statusTextEl   = document.getElementById('status-text');
const coordsEl       = document.getElementById('coords');
const latEl          = document.getElementById('lat');
const lngEl          = document.getElementById('lng');
const permCard       = document.getElementById('perm-card');
const permGrantBtn   = document.getElementById('perm-grant-btn');
const errorBar       = document.getElementById('error-bar');
const deviceNameEl   = document.getElementById('device-name');
const deviceEditBtn  = document.getElementById('device-edit-btn');
const toast          = document.getElementById('toast');

// overlays
const saveOverlay    = document.getElementById('save-overlay');
const filenameInput  = document.getElementById('filename-input');
const saveCancelBtn  = document.getElementById('save-cancel');
const saveConfirmBtn = document.getElementById('save-confirm');

const deviceOverlay  = document.getElementById('device-overlay');
const deviceInput    = document.getElementById('device-input');
const deviceCancelBtn= document.getElementById('device-cancel');
const deviceSaveBtn  = document.getElementById('device-save');

// ── Boot ──────────────────────────────────────────────────────────────────
function boot() {
  const stored = localStorage.getItem(DEVICE_NAME_KEY);
  if (!stored) {
    showDeviceModal(/* firstTime */ true);
  } else {
    setDeviceName(stored);
  }
  checkPermissions();
}

// ── Device name ───────────────────────────────────────────────────────────
function setDeviceName(name) {
  localStorage.setItem(DEVICE_NAME_KEY, name);
  deviceNameEl.textContent = name;
}

function showDeviceModal(firstTime = false) {
  deviceInput.value = localStorage.getItem(DEVICE_NAME_KEY) ?? '';
  deviceInput.placeholder = firstTime ? 'e.g. Redmi Note 12' : 'Device name';
  deviceCancelBtn.style.display = firstTime ? 'none' : '';
  deviceOverlay.classList.add('visible');
  setTimeout(() => deviceInput.focus(), 50);
}

deviceEditBtn.addEventListener('click', () => showDeviceModal());

deviceSaveBtn.addEventListener('click', () => {
  const name = deviceInput.value.trim();
  if (!name) return;
  setDeviceName(name);
  deviceOverlay.classList.remove('visible');
});

deviceCancelBtn.addEventListener('click', () => {
  deviceOverlay.classList.remove('visible');
});

deviceInput.addEventListener('keydown', (e) => {
  if (e.key === 'Enter') deviceSaveBtn.click();
});

// ── Permissions ───────────────────────────────────────────────────────────
async function checkPermissions() {
  // Permissions API only tells us about mic; geolocation has no query support everywhere
  try {
    const mic = await navigator.permissions.query({ name: 'microphone' });
    if (mic.state === 'granted') {
      permCard.style.display = 'none';
      recordBtn.disabled = false;
    } else {
      permCard.style.display = '';
      recordBtn.disabled = true;
    }
    mic.onchange = () => checkPermissions();
  } catch {
    // Permissions API not available — just show the grant card
    permCard.style.display = '';
  }
}

permGrantBtn.addEventListener('click', async () => {
  try {
    const stream = await navigator.mediaDevices.getUserMedia({ audio: true });
    stream.getTracks().forEach(t => t.stop());
    permCard.style.display = 'none';
    recordBtn.disabled = false;
  } catch (err) {
    showError('Microphone permission denied. Please allow access in your browser settings.');
  }
});

// ── GPS ───────────────────────────────────────────────────────────────────
function captureLocation() {
  return new Promise((resolve) => {
    if (!navigator.geolocation) { resolve(null); return; }
    const timeout = setTimeout(() => resolve(null), 5000);
    navigator.geolocation.getCurrentPosition(
      (pos) => {
        clearTimeout(timeout);
        resolve({
          latitude:  pos.coords.latitude,
          longitude: pos.coords.longitude,
          accuracy:  pos.coords.accuracy,
        });
      },
      () => { clearTimeout(timeout); resolve(null); },
      { enableHighAccuracy: true, timeout: 5000, maximumAge: 0 }
    );
  });
}

// ── Recording ─────────────────────────────────────────────────────────────
recordBtn.addEventListener('click', () => {
  if (mediaRecorder && mediaRecorder.state === 'recording') {
    stopRecording();
  } else {
    startRecording();
  }
});

async function startRecording() {
  clearError();
  let stream;
  try {
    stream = await navigator.mediaDevices.getUserMedia({ audio: true });
  } catch (err) {
    showError('Could not access microphone: ' + err.message);
    return;
  }

  // Capture GPS in parallel — never blocks recording
  capturedCoords = null;
  captureLocation().then(coords => { capturedCoords = coords; });

  audioChunks = [];
  mediaRecorder = new MediaRecorder(stream, PREFERRED_MIME ? { mimeType: PREFERRED_MIME } : {});
  mediaRecorder.addEventListener('dataavailable', e => {
    if (e.data.size > 0) audioChunks.push(e.data);
  });
  mediaRecorder.addEventListener('stop', onRecordingStopped);
  mediaRecorder.addEventListener('error', (e) => {
    showError('Recording error: ' + e.error?.message);
    stopRecording();
  });

  mediaRecorder.start();
  startTime = new Date();

  setRecordingUi(true);
  startTimer();
}

function stopRecording() {
  if (!mediaRecorder) return;
  mediaRecorder.stop();
  mediaRecorder.stream.getTracks().forEach(t => t.stop());
  stopTimer();
  setRecordingUi(false);
}

function onRecordingStopped() {
  const blob      = new Blob(audioChunks, { type: mediaRecorder.mimeType || PREFERRED_MIME });
  const stopDate  = new Date();
  const duration  = Math.round((stopDate - startTime) / 1000);
  const suggested = buildSuggestedFilename();

  // Pass data to save dialog
  saveOverlay.dataset.blob      = '';          // can't store blob in dataset; use module-level
  pendingSave = { blob, duration, stopDate };
  filenameInput.value = suggested;
  saveOverlay.classList.add('visible');
  setTimeout(() => filenameInput.select(), 50);
}

// Module-level pending save bag
let pendingSave = null;

saveCancelBtn.addEventListener('click', () => {
  saveOverlay.classList.remove('visible');
  pendingSave = null;
});

saveConfirmBtn.addEventListener('click', () => {
  const { blob, duration, stopDate } = pendingSave ?? {};
  if (!blob) return;

  const rawName   = filenameInput.value.trim() || buildSuggestedFilename();
  const baseName  = rawName.replace(/\.(webm|m4a|ogg)$/i, '');
  const audioName = `${baseName}.${FILE_EXT}`;
  const jsonName  = `${baseName}.json`;
  const metadata  = buildMetadata(audioName, duration, stopDate);

  downloadBlob(blob, audioName, blob.type);
  downloadBlob(
    new Blob([JSON.stringify(metadata, null, 2)], { type: 'application/json' }),
    jsonName,
    'application/json'
  );

  saveOverlay.classList.remove('visible');
  pendingSave = null;
  showToast(`Saved ${audioName}`);
});

filenameInput.addEventListener('keydown', (e) => {
  if (e.key === 'Enter') saveConfirmBtn.click();
  if (e.key === 'Escape') saveCancelBtn.click();
});

// ── Metadata ──────────────────────────────────────────────────────────────
function buildMetadata(filename, durationSeconds, stopDate) {
  const label = extractLabel(filename);
  const startedLocal = startTime.toISOString().replace('Z', getLocalOffsetString(startTime));
  const startedUtc   = startTime.toISOString();

  return {
    label,
    filename,
    latitude:           capturedCoords?.latitude  ?? null,
    longitude:          capturedCoords?.longitude ?? null,
    location_accuracy_m: capturedCoords?.accuracy ?? null,
    started_at_local:   startedLocal,
    started_at_utc:     startedUtc,
    duration_seconds:   durationSeconds,
    encoding:           mediaRecorder?.mimeType ?? PREFERRED_MIME,
    device_model:       localStorage.getItem(DEVICE_NAME_KEY) ?? 'unknown',
    platform:           navigator.platform,
    app_version:        APP_VERSION,
  };
}

function extractLabel(filename) {
  const base = filename.replace(/\.(webm|m4a|ogg|mp4)$/i, '');
  const label = base.split('_')[0].replace(/[0-9]/g, '').toLowerCase();
  return label || 'misc';
}

function getLocalOffsetString(date) {
  const offsetMins = -date.getTimezoneOffset();
  const sign = offsetMins >= 0 ? '+' : '-';
  const abs  = Math.abs(offsetMins);
  const hh   = String(Math.floor(abs / 60)).padStart(2, '0');
  const mm   = String(abs % 60).padStart(2, '0');
  return `${sign}${hh}:${mm}`;
}

// ── Timer ─────────────────────────────────────────────────────────────────
function startTimer() {
  timerInterval = setInterval(() => {
    const elapsed = Math.floor((Date.now() - startTime) / 1000);
    timerEl.textContent = formatDuration(elapsed);
  }, 1000);
}

function stopTimer() {
  clearInterval(timerInterval);
  timerInterval = null;
  timerEl.textContent = '00:00';
}

function formatDuration(seconds) {
  const m = Math.floor(seconds / 60).toString().padStart(2, '0');
  const s = (seconds % 60).toString().padStart(2, '0');
  return `${m}:${s}`;
}

// ── UI helpers ────────────────────────────────────────────────────────────
function setRecordingUi(isRecording) {
  recordBtn.classList.toggle('active', isRecording);
  recordRing.classList.toggle('active', isRecording);
  recordLabel.textContent = isRecording ? 'Stop' : 'Record';

  if (isRecording) {
    statusTextEl.textContent = 'Recording in progress — tap Stop when done.';
    coordsEl.style.display = 'flex';
    // Update coords after GPS resolves (up to 5s)
    const poll = setInterval(() => {
      if (capturedCoords) {
        latEl.textContent = capturedCoords.latitude.toFixed(5);
        lngEl.textContent = capturedCoords.longitude.toFixed(5);
        clearInterval(poll);
      }
    }, 500);
    setTimeout(() => {
      clearInterval(poll);
      if (!capturedCoords) {
        latEl.textContent = 'unavailable';
        lngEl.textContent = 'unavailable';
      }
    }, 6000);
  } else {
    statusTextEl.textContent = 'Ready to record';
    coordsEl.style.display = 'none';
  }
}

function buildSuggestedFilename() {
  const now  = new Date();
  const pad  = (n) => String(n).padStart(2, '0');
  const ts   = `${now.getFullYear()}${pad(now.getMonth()+1)}${pad(now.getDate())}_${pad(now.getHours())}${pad(now.getMinutes())}${pad(now.getSeconds())}`;
  return `misc_${ts}`;
}

function downloadBlob(blob, filename, type) {
  const url = URL.createObjectURL(blob);
  const a   = document.createElement('a');
  a.href     = url;
  a.download = filename;
  a.click();
  setTimeout(() => URL.revokeObjectURL(url), 5000);
}

function showError(msg) {
  errorBar.textContent = msg;
  errorBar.classList.add('visible');
}

function clearError() {
  errorBar.classList.remove('visible');
}

function showToast(msg) {
  toast.textContent = msg;
  toast.classList.add('visible');
  setTimeout(() => toast.classList.remove('visible'), 3000);
}

// ── Init ──────────────────────────────────────────────────────────────────
boot();
