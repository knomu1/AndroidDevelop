// ブラウザ側の WebRTC 受信。Blazor (Home.razor) から JS interop で呼ばれる。
// 流れ: WebSocket(シグナリング) で Android の offer を受け取り → answer を返す → ICE を交換 → <video> に映像が出る。

let dotnet = null;
let video = null;
let ws = null;
let pc = null;
let statsTimer = null;
let reconnectTimer = null;
let prev = null; // 前回の統計(ビットレートなどの差分計算用)
let disposed = false;

export function start(videoId, ref) {
    dotnet = ref;
    video = document.getElementById(videoId);
    disposed = false;
    connectSignaling();
}

export function stop() {
    disposed = true;
    clearTimeout(reconnectTimer);
    closePc();
    if (ws) ws.close();
}

/** Android へ画質設定を送る。codec は "H264" / "VP8" / "VP9" / "AV1"。 */
export function sendConfig(width, height, fps, bitrateKbps, codec) {
    send({ type: 'config', width, height, fps, bitrateKbps, codec });
}

function send(obj) {
    if (ws && ws.readyState === WebSocket.OPEN) ws.send(JSON.stringify(obj));
}

function state(s) {
    dotnet.invokeMethodAsync('OnState', s);
}

function connectSignaling() {
    if (disposed) return;
    const proto = location.protocol === 'https:' ? 'wss' : 'ws';
    ws = new WebSocket(`${proto}://${location.host}/ws?role=viewer`);
    ws.onopen = () => state('signaling-connected');
    ws.onclose = () => {
        state('signaling-closed');
        closePc();
        reconnectTimer = setTimeout(connectSignaling, 1500);
    };
    ws.onmessage = async (e) => {
        const m = JSON.parse(e.data);
        switch (m.type) {
            case 'offer': await onOffer(m); break;
            case 'candidate':
                if (pc) await pc.addIceCandidate({ candidate: m.candidate, sdpMid: m.sdpMid, sdpMLineIndex: m.sdpMLineIndex });
                break;
            case 'peer-left': closePc(); state('sender-left'); break;
            case 'sender-stats': dotnet.invokeMethodAsync('OnSenderStats', e.data); break;
        }
    };
}

async function onOffer(m) {
    closePc();
    pc = new RTCPeerConnection({ iceServers: [] }); // 同一 LAN なので STUN/TURN は不要
    pc.onicecandidate = (e) => {
        if (e.candidate) send({ type: 'candidate', candidate: e.candidate.candidate, sdpMid: e.candidate.sdpMid, sdpMLineIndex: e.candidate.sdpMLineIndex });
    };
    pc.onconnectionstatechange = () => state('pc-' + pc.connectionState);
    pc.oniceconnectionstatechange = () => fetch('/api/viewer-stats', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ event: 'ice-' + pc.iceConnectionState, at: new Date().toISOString() }) }).catch(() => { });
    pc.ontrack = (e) => {
        video.srcObject = e.streams[0] ?? new MediaStream([e.track]);
        // 受信側のバッファを最小に(低遅延優先)。対応していないブラウザでは無視される。
        try { e.receiver.jitterBufferTarget = 0; } catch { }
        video.play().catch(() => { });
    };
    await pc.setRemoteDescription({ type: 'offer', sdp: m.sdp });
    const answer = await pc.createAnswer();
    await pc.setLocalDescription(answer);
    send({ type: 'answer', sdp: answer.sdp });
    prev = null;
    clearInterval(statsTimer);
    statsTimer = setInterval(collectStats, 1000);
}

function closePc() {
    clearInterval(statsTimer);
    if (pc) { pc.close(); pc = null; }
    if (video) video.srcObject = null;
}

/** 選ばれた経路(ローカル/リモートの IP・ポート・種別)を文字列にする。接続できないときの切り分け用。 */
function describePath(local, remote) {
    const f = (c) => c ? `${c.address ?? '?'}:${c.port ?? ''} (${c.candidateType ?? ''}/${c.protocol ?? ''})` : '?';
    return `${f(local)} <-> ${f(remote)}`;
}

async function collectStats() {
    if (!pc) return;
    const report = await pc.getStats();
    let inbound = null, pair = null;
    const codecs = {}, cands = {};
    report.forEach(r => {
        if (r.type === 'inbound-rtp' && r.kind === 'video') inbound = r;
        if (r.type === 'candidate-pair' && r.nominated && r.state === 'succeeded') pair = r;
        if (r.type === 'codec') codecs[r.id] = r;
        if (r.type === 'local-candidate' || r.type === 'remote-candidate') cands[r.id] = r;
    });
    if (!inbound) return;

    const now = performance.now();
    let kbps = 0;
    if (prev) kbps = ((inbound.bytesReceived - prev.bytes) * 8) / (now - prev.t); // bit/ms = kbit/s
    prev = { bytes: inbound.bytesReceived, t: now };

    const jbDelayMs = inbound.jitterBufferEmittedCount > 0
        ? (inbound.jitterBufferDelay / inbound.jitterBufferEmittedCount) * 1000 : 0;
    const stats = {
        width: inbound.frameWidth ?? 0,
        height: inbound.frameHeight ?? 0,
        fps: Math.round((inbound.framesPerSecond ?? 0) * 10) / 10,
        bitrateKbps: Math.round(kbps),
        codec: codecs[inbound.codecId]?.mimeType ?? '',
        decoder: inbound.decoderImplementation ?? '',
        framesDecoded: inbound.framesDecoded ?? 0,
        framesDropped: inbound.framesDropped ?? 0,
        packetsLost: inbound.packetsLost ?? 0,
        jitterMs: Math.round((inbound.jitter ?? 0) * 1000),
        jitterBufferDelayMs: Math.round(jbDelayMs),
        rttMs: pair?.currentRoundTripTime != null ? Math.round(pair.currentRoundTripTime * 1000) : null,
        freezeCount: inbound.freezeCount ?? 0,
        // 画面に実際に表示されている映像の向き・大きさ(スマホを縦横にすると、ここが入れ替わる)
        displayWidth: video?.videoWidth ?? 0,
        displayHeight: video?.videoHeight ?? 0,
        path: pair ? describePath(cands[pair.localCandidateId], cands[pair.remoteCandidateId]) : '',
    };
    dotnet.invokeMethodAsync('OnStats', JSON.stringify(stats));
    fetch('/api/viewer-stats', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(stats) }).catch(() => { });
}