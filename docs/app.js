/* LocalPaint WebGPU POC. MIT-licensed implementation. Inference stays in the browser. */
(() => {
  'use strict';
  const $ = (id) => document.getElementById(id);
  const S = 512;
  const SIZE = S * S;
  const MODEL_URL = 'https://huggingface.co/lxfater/inpaint-web/resolve/main/migan.onnx';
  const ORT_PATH = 'https://cdn.jsdelivr.net/npm/onnxruntime-web@1.30.0/dist/';
  const BASE = $('base-canvas');
  const BASE_CTX = BASE.getContext('2d', { willReadFrequently: true });
  const VISIBLE = $('mask-canvas');
  const VISIBLE_CTX = VISIBLE.getContext('2d');
  const MASK = document.createElement('canvas');
  MASK.width = MASK.height = S;
  const MASK_CTX = MASK.getContext('2d', { willReadFrequently: true });
  const RESULT = $('result-canvas');
  const RESULT_CTX = RESULT.getContext('2d');

  let session = null;
  let sourceLoaded = false;
  let finished = false;
  let loadingModel = false;
  let running = false;
  let drawing = false;
  let undoStack = [];
  let activePointer = null;
  let lastXY = null;
  let runStart = 0;
  let ticker = null;
  let currentEngine = 'webgpu';

  function setStatus(message) { $('run-status').textContent = message; }
  function setModelStatus(message) { $('model-status').textContent = message; }
  function badge(label, tone) { const el = $('model-badge'); el.textContent = label; el.className = 'state ' + tone; }
  function setModelMeter(percent, label) {
    $('model-track').hidden = false;
    $('model-meter').style.width = `${Math.max(0, Math.min(100, percent))}%`;
    if (label) setModelStatus(label);
  }
  function humanBytes(n) { return (n / 1048576).toFixed(1) + ' MiB'; }
  function errMessage(error) { return error?.message || String(error); }
  function refreshButtons() {
    $('run-inpaint').disabled = !session || !sourceLoaded || running || loadingModel;
    $('load-model').disabled = running || loadingModel;
    $('import-onnx').disabled = running || loadingModel;
    $('backend').disabled = running || loadingModel || !!session;
    $('pick-image').disabled = running;
    $('sample-image').disabled = running;
    $('clear-mask').disabled = !sourceLoaded || running;
    $('undo').disabled = !undoStack.length || running;
    $('save-png').disabled = !finished || running;
    $('use-result').disabled = !finished || running;
  }
  async function checkGPU() {
    $('memory-text').textContent = navigator.deviceMemory ? `${navigator.deviceMemory} GB (reported)` : 'Not exposed';
    if (!window.isSecureContext) {
      $('device-pill').textContent = 'HTTPS required';
      $('device-pill').className = 'pill bad';
      $('adapter-text').textContent = 'Use an HTTPS site or localhost';
      return;
    }
    if (!navigator.gpu) {
      $('device-pill').textContent = 'WebGPU unavailable';
      $('device-pill').className = 'pill bad';
      $('adapter-text').textContent = 'Try updated Chrome on Android';
      return;
    }
    try {
      const adapter = await navigator.gpu.requestAdapter({ powerPreference: 'high-performance' });
      if (!adapter) throw new Error('No adapter');
      const info = adapter.info || {};
      $('adapter-text').textContent = [info.vendor, info.architecture].filter(Boolean).join(' / ') || 'GPU available';
      $('buffer-text').textContent = `${Math.floor(adapter.limits.maxBufferSize / (1024 * 1024))} MiB`;
      $('device-pill').textContent = 'WebGPU available';
      $('device-pill').className = 'pill good';
      if (navigator.storage?.estimate) {
        const estimate = await navigator.storage.estimate();
        if (estimate.quota) $('memory-text').textContent += ` · ${(estimate.quota / 1073741824).toFixed(1)} GB storage quota`;
      }
    } catch (error) {
      $('adapter-text').textContent = 'GPU adapter error: ' + errMessage(error);
      $('device-pill').textContent = 'WebGPU unavailable';
      $('device-pill').className = 'pill bad';
    }
  }
  function renderOverlay() {
    VISIBLE_CTX.clearRect(0, 0, S, S);
    VISIBLE_CTX.drawImage(MASK, 0, 0);
    VISIBLE_CTX.globalCompositeOperation = 'source-in';
    VISIBLE_CTX.fillStyle = 'rgba(255,83,100,.55)';
    VISIBLE_CTX.fillRect(0, 0, S, S);
    VISIBLE_CTX.globalCompositeOperation = 'source-over';
  }
  function snapshot() {
    if (undoStack.length >= 10) undoStack.shift();
    undoStack.push(MASK_CTX.getImageData(0, 0, S, S));
    refreshButtons();
  }
  function clearMask(pushUndo = false) {
    if (pushUndo) snapshot();
    MASK_CTX.clearRect(0, 0, S, S);
    renderOverlay();
    refreshButtons();
  }
  function pointerToXY(event) {
    const rect = VISIBLE.getBoundingClientRect();
    return { x: (event.clientX - rect.left) * S / rect.width, y: (event.clientY - rect.top) * S / rect.height };
  }
  function stroke(a, b) {
    MASK_CTX.save();
    MASK_CTX.globalCompositeOperation = $('eraser').checked ? 'destination-out' : 'source-over';
    MASK_CTX.strokeStyle = '#fff';
    MASK_CTX.fillStyle = '#fff';
    MASK_CTX.lineCap = 'round';
    MASK_CTX.lineJoin = 'round';
    MASK_CTX.lineWidth = Number($('brush').value);
    MASK_CTX.beginPath();
    MASK_CTX.moveTo(a.x, a.y);
    MASK_CTX.lineTo(b.x, b.y);
    MASK_CTX.stroke();
    MASK_CTX.beginPath();
    MASK_CTX.arc(b.x, b.y, MASK_CTX.lineWidth / 2, 0, Math.PI * 2);
    MASK_CTX.fill();
    MASK_CTX.restore();
    renderOverlay();
  }
  VISIBLE.addEventListener('pointerdown', (e) => {
    if (!sourceLoaded || running || drawing) return;
    e.preventDefault();
    snapshot();
    drawing = true;
    activePointer = e.pointerId;
    VISIBLE.setPointerCapture(e.pointerId);
    lastXY = pointerToXY(e);
    stroke(lastXY, lastXY);
  });
  VISIBLE.addEventListener('pointermove', (e) => {
    if (!drawing || e.pointerId !== activePointer) return;
    e.preventDefault();
    const xy = pointerToXY(e);
    stroke(lastXY, xy);
    lastXY = xy;
  });
  function stopStroke(e) { if (drawing && e.pointerId === activePointer) { drawing = false; activePointer = null; lastXY = null; refreshButtons(); } }
  VISIBLE.addEventListener('pointerup', stopStroke);
  VISIBLE.addEventListener('pointercancel', stopStroke);
  $('brush').addEventListener('input', () => { $('brush-value').textContent = $('brush').value + ' px'; });
  $('clear-mask').addEventListener('click', () => clearMask(true));
  $('undo').addEventListener('click', () => {
    const snap = undoStack.pop();
    if (!snap) return;
    MASK_CTX.putImageData(snap, 0, 0);
    renderOverlay();
    refreshButtons();
  });
  function resetOutput() {
    RESULT_CTX.clearRect(0, 0, S, S);
    $('result-empty').hidden = false;
    $('last-timing').textContent = 'No generation yet.';
    finished = false;
    refreshButtons();
  }
  async function importImage(file) {
    if (!file) return;
    if (!file.type.startsWith('image/')) { setStatus('Please choose an image file.'); return; }
    let bitmap;
    try {
      bitmap = await createImageBitmap(file, { imageOrientation: 'from-image' });
      BASE_CTX.fillStyle = '#ecf2f4';
      BASE_CTX.fillRect(0, 0, S, S);
      const scale = Math.min(S / bitmap.width, S / bitmap.height);
      const dw = Math.round(bitmap.width * scale);
      const dh = Math.round(bitmap.height * scale);
      BASE_CTX.drawImage(bitmap, Math.round((S - dw) / 2), Math.round((S - dh) / 2), dw, dh);
      bitmap.close();
      sourceLoaded = true;
      $('empty-hint').hidden = true;
      undoStack = [];
      clearMask();
      resetOutput();
      setStatus(`Image ready (${dw} × ${dh} fitted into 512 square). Paint a mask to inpaint.`);
    } catch (error) {
      bitmap?.close?.();
      setStatus('Unable to open image: ' + errMessage(error));
    }
  }
  $('pick-image').addEventListener('click', () => $('image-file').click());
  $('image-file').addEventListener('change', async (e) => { await importImage(e.target.files?.[0]); e.target.value = ''; });
  $('sample-image').addEventListener('click', () => {
    BASE_CTX.fillStyle = '#c5d6e7'; BASE_CTX.fillRect(0,0,S,S);
    BASE_CTX.fillStyle = '#9ab4a3'; BASE_CTX.fillRect(0,290,S,222);
    BASE_CTX.fillStyle = '#ece7db'; BASE_CTX.fillRect(25,135,460,215);
    BASE_CTX.fillStyle = '#bdcbd0'; BASE_CTX.fillRect(50,155,100,170);
    BASE_CTX.fillStyle = '#4d6377'; BASE_CTX.fillRect(170,195,100,155);
    BASE_CTX.fillStyle = '#ccaa90'; BASE_CTX.fillRect(295,165,150,185);
    BASE_CTX.fillStyle = '#4c8e74'; BASE_CTX.fillRect(70,375,360,55);
    BASE_CTX.fillStyle = '#df7c77'; BASE_CTX.beginPath(); BASE_CTX.arc(275,305,62,0,Math.PI*2); BASE_CTX.fill();
    BASE_CTX.fillStyle = '#496473'; BASE_CTX.beginPath(); BASE_CTX.arc(275,285,34,0,Math.PI*2); BASE_CTX.fill();
    sourceLoaded = true; $('empty-hint').hidden = true; undoStack = []; clearMask(); resetOutput();
    setStatus('Sample ready. Try painting over the round object in the centre, then Run local inpainting.');
  });

  async function downloadModel() {
    setModelStatus('Checking browser cache…');
    let response;
    let cache;
    try {
      if ('caches' in window) {
        cache = await caches.open('localpaint-migan-v1');
        response = await cache.match(MODEL_URL);
      }
    } catch (error) { console.warn('Cache unavailable:', error); }
    if (response) {
      setModelMeter(100, 'Model found in local cache. Reading weights…');
      return new Uint8Array(await response.arrayBuffer());
    }
    const res = await fetch(MODEL_URL);
    if (!res.ok) throw new Error(`Model download failed (HTTP ${res.status}). Try importing the .onnx file manually.`);
    if (!res.body?.getReader) {
      const bytes = new Uint8Array(await res.arrayBuffer());
      setModelMeter(100, `Downloaded ${humanBytes(bytes.byteLength)}. Preparing runtime…`);
      if (cache) await cache.put(MODEL_URL, new Response(bytes.slice(), { headers: { 'Content-Type': 'application/octet-stream' } })).catch(() => {});
      return bytes;
    }
    const reader = res.body.getReader();
    const total = Number(res.headers.get('content-length')) || 29490000;
    const chunks = [];
    let size = 0;
    while (true) {
      const { done, value } = await reader.read();
      if (done) break;
      chunks.push(value);
      size += value.byteLength;
      setModelMeter(Math.min(99, size / total * 100), `Downloading model: ${humanBytes(size)} / ${humanBytes(total)} (estimated)`);
    }
    const bytes = new Uint8Array(size);
    let offset = 0;
    for (const chunk of chunks) { bytes.set(chunk, offset); offset += chunk.byteLength; }
    setModelMeter(100, `Downloaded ${humanBytes(size)}. Caching and loading weights…`);
    if (cache) {
      try { await cache.put(MODEL_URL, new Response(bytes.slice(), { headers: { 'Content-Type': 'application/octet-stream' } })); }
      catch (error) { console.warn('Unable to cache model', error); }
    }
    return bytes;
  }
  async function createSession(modelBytes) {
    if (typeof ort === 'undefined') throw new Error('ONNX Runtime script could not load. Check your connection and refresh.');
    ort.env.wasm.wasmPaths = ORT_PATH;
    ort.env.wasm.numThreads = 1;
    const engine = $('backend').value;
    if (engine === 'webgpu' && !navigator.gpu) throw new Error('WebGPU unavailable. Use HTTPS in Chrome, or select CPU fallback.');
    const start = performance.now();
    setModelStatus(`Initialising ${engine === 'webgpu' ? 'WebGPU' : 'WASM CPU'} inference session. May take a little while…`);
    const candidate = await ort.InferenceSession.create(modelBytes, {
      executionProviders: [engine],
      graphOptimizationLevel: 'all',
    });
    // This POC only supports MI-GAN's [1,4,512,512] input contract.
    const meta = candidate.inputMetadata?.[candidate.inputNames[0]];
    const dims = meta?.dimensions || meta?.dims;
    if (Array.isArray(dims) && dims.length === 4 && Number(dims[1]) !== 4) {
      await candidate.release();
      throw new Error('This ONNX file is not the supported MI-GAN 4-channel model. Use migan.onnx.');
    }
    session = candidate;
    currentEngine = engine;
    const elapsed = ((performance.now() - start) / 1000).toFixed(1);
    badge(`MI-GAN ready · ${engine.toUpperCase()}`, 'good');
    setModelMeter(100, `MI-GAN loaded into the ${engine.toUpperCase()} engine in ${elapsed}s. Ready to paint.`);
    setStatus('Model loaded. Choose an image and paint a red mask to run local AI inpainting.');
  }
  async function loadModel(file = null) {
    if (loadingModel || running || session) return;
    loadingModel = true; badge('Loading model…', 'muted'); refreshButtons();
    try {
      const bytes = file ? new Uint8Array(await file.arrayBuffer()) : await downloadModel();
      if (file) setModelMeter(100, `Read ${file.name} (${humanBytes(bytes.length)}). Checking model…`);
      await createSession(bytes);
    } catch (error) {
      badge('Model load failed', 'bad');
      setModelStatus(`ERROR: ${errMessage(error)}. Check Chrome WebGPU support, or switch to CPU fallback.`);
      console.error(error);
    } finally { loadingModel = false; refreshButtons(); }
  }
  $('load-model').addEventListener('click', () => loadModel());
  $('import-onnx').addEventListener('click', () => $('onnx-file').click());
  $('onnx-file').addEventListener('change', (e) => { if (e.target.files?.[0]) loadModel(e.target.files[0]); e.target.value = ''; });

  function startTimer() {
    runStart = performance.now();
    clearInterval(ticker);
    const tick = () => { $('elapsed').textContent = ((performance.now() - runStart) / 1000).toFixed(1) + 's'; };
    tick(); ticker = setInterval(tick, 200);
  }
  function stopTimer() { clearInterval(ticker); ticker = null; }
  function tensorsForMigan() {
    const image = BASE_CTX.getImageData(0, 0, S, S).data;
    const paint = MASK_CTX.getImageData(0, 0, S, S).data;
    const input = new Float32Array(SIZE * 4);
    let selected = 0;
    for (let i = 0; i < SIZE; i++) {
      const keep = paint[i * 4 + 3] >= 64 ? 0 : 1;
      if (!keep) selected++;
      input[i] = keep - .5;
      input[SIZE + i] = (image[i * 4] / 127.5 - 1) * keep;
      input[SIZE * 2 + i] = (image[i * 4 + 1] / 127.5 - 1) * keep;
      input[SIZE * 3 + i] = (image[i * 4 + 2] / 127.5 - 1) * keep;
    }
    return { input, selected, image, paint };
  }
  async function inpaint() {
    if (!sourceLoaded || !session || running) return;
    const { input, selected, image, paint } = tensorsForMigan();
    if (selected === 0) { setStatus('Paint a red area first. The brush defines the region that the model replaces.'); return; }
    running = true; refreshButtons(); startTimer();
    setStatus(`Running ONE MI-GAN inference on ${currentEngine.toUpperCase()}. ${selected.toLocaleString()} masked pixels. No diffusion step counter for this model…`);
    try {
      await new Promise(resolve => setTimeout(resolve, 70));
      const feed = {};
      feed[session.inputNames[0]] = new ort.Tensor('float32', input, [1, 4, S, S]);
      const outputs = await session.run(feed);
      const output = outputs[session.outputNames[0]];
      if (!output || !output.data || output.data.length < SIZE * 3) throw new Error('Model returned an unexpected output tensor.');
      const data = output.data;
      const finalImage = new ImageData(S, S);
      let maxAbs = 0;
      for (let j = 0; j < Math.min(1024, data.length); j++) maxAbs = Math.max(maxAbs, Math.abs(data[j]));
      // MI-GAN's native output is normalized to [-1,1].
      for (let i = 0; i < SIZE; i++) {
        const alpha = paint[i * 4 + 3] / 255;
        const j = i * 4;
        for (let ch = 0; ch < 3; ch++) {
          const generated = (data[ch * SIZE + i] + 1) * 127.5;
          const blended = image[j + ch] * (1 - alpha) + generated * alpha;
          finalImage.data[j + ch] = Math.max(0, Math.min(255, Math.round(blended)));
        }
        finalImage.data[j + 3] = 255;
      }
      RESULT_CTX.putImageData(finalImage, 0, 0);
      $('result-empty').hidden = true;
      finished = true;
      const secs = ((performance.now() - runStart) / 1000).toFixed(1);
      $('last-timing').textContent = `${secs}s · ${currentEngine.toUpperCase()} · 512 × 512`;
      setStatus(`Inpainting finished in ${secs}s. Output is ready to save; only the painted region was changed.`);
    } catch (error) {
      finished = false;
      setStatus(`Inference failed: ${errMessage(error)}. Try WASM mode after reloading, or use a smaller image/model.`);
      console.error(error);
    } finally { stopTimer(); running = false; refreshButtons(); }
  }
  $('run-inpaint').addEventListener('click', inpaint);
  $('save-png').addEventListener('click', () => {
    if (!finished) return;
    RESULT.toBlob(blob => {
      if (!blob) { setStatus('Unable to export PNG.'); return; }
      const url = URL.createObjectURL(blob);
      const a = document.createElement('a'); a.href = url; a.download = 'localpaint-inpaint-' + Date.now() + '.png';
      document.body.appendChild(a); a.click(); a.remove(); setTimeout(() => URL.revokeObjectURL(url), 5000);
    }, 'image/png');
  });
  $('use-result').addEventListener('click', () => {
    if (!finished) return;
    BASE_CTX.drawImage(RESULT, 0, 0);
    undoStack = []; clearMask(); resetOutput();
    setStatus('Last result is now your source image. Paint a new region.');
  });
  // Safetensors format: 8-byte LE JSON header length, followed by metadata, followed by tensor bytes.
  async function inspectSafetensors(file) {
    const output = $('checkpoint-output');
    output.textContent = 'Reading only the safetensors header…';
    try {
      if (!file.name.toLowerCase().endsWith('.safetensors')) throw new Error('Select a .safetensors checkpoint.');
      const prefix = await file.slice(0, 8).arrayBuffer();
      if (prefix.byteLength !== 8) throw new Error('File too short.');
      const lengthBig = new DataView(prefix).getBigUint64(0, true);
      if (lengthBig > 64n * 1024n * 1024n) throw new Error('Header is unusually large (>64 MiB). Refusing to allocate it.');
      const length = Number(lengthBig);
      if (length < 2 || length + 8 > file.size) throw new Error('Invalid safetensors header length.');
      const headerText = await file.slice(8, 8 + length).text();
      const header = JSON.parse(headerText);
      const keys = Object.keys(header).filter(key => key !== '__metadata__');
      const dtypes = {};
      const prefixCounts = {};
      for (const k of keys) {
        const dt = header[k]?.dtype || '?';
        dtypes[dt] = (dtypes[dt] || 0) + 1;
        const stem = k.split('.').slice(0, 2).join('.');
        prefixCounts[stem] = (prefixCounts[stem] || 0) + 1;
      }
      const firstConv = keys.find(k => k.endsWith('input_blocks.0.0.weight'));
      const convShape = firstConv ? header[firstConv]?.shape : null;
      const channels = Array.isArray(convShape) ? convShape[1] : null;
      const likelySD = keys.some(k => /model\.diffusion_model|diffusion_model/.test(k));
      const metadata = header.__metadata__ || {};
      const leading = Object.entries(prefixCounts).sort((a,b) => b[1]-a[1]).slice(0, 6).map(([name,n]) => `  ${name}: ${n}`).join('\n');
      const info = [
        `Filename: ${file.name}`,
        `Total file: ${humanBytes(file.size)} (${(file.size / 1073741824).toFixed(2)} GiB)`,
        `Header read: ${(length / 1024).toFixed(1)} KiB (${((length + 8) / file.size * 100).toFixed(3)}% of file)`,
        `Tensor count: ${keys.length}`,
        `Dtypes: ${Object.entries(dtypes).map(([k,v])=>k+': '+v).join(', ') || 'none'}`,
        `Diffusion-like weight names: ${likelySD ? 'yes' : 'not detected'}`,
        `First input conv: ${convShape ? convShape.join(' × ') : 'not found'}`,
        `UNet input channels: ${channels ?? 'unknown'} ${channels === 4 ? '(typical SD 1.5 img2img)' : channels === 9 ? '(dedicated inpainting UNet)' : ''}`,
        `Top tensor name prefixes:\n${leading || '  none'}`,
        `Metadata: ${JSON.stringify(metadata).slice(0, 900)}`,
        '',
        'IMPORTANT: This is a metadata inspection only. A large file or compatible tensor names DO NOT establish that the model runs in the browser.'
      ];
      output.textContent = info.join('\n');
    } catch (error) { output.textContent = 'Inspection error: ' + errMessage(error); }
  }
  $('inspect-model').addEventListener('click', () => $('safetensors-file').click());
  $('safetensors-file').addEventListener('change', (e) => { if (e.target.files?.[0]) inspectSafetensors(e.target.files[0]); e.target.value = ''; });
  $('backend').addEventListener('change', () => { if (session) return; setModelStatus($('backend').value === 'webgpu' ? 'GPU mode selected. MI-GAN model has not been loaded yet.' : 'CPU fallback selected. This may run slower but can help diagnose WebGPU issues.'); });
  BASE_CTX.fillStyle = '#edf2f6'; BASE_CTX.fillRect(0,0,S,S);
  resetOutput(); refreshButtons(); checkGPU();
})();