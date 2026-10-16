using System.Net.WebSockets;
using System.Text;

namespace WebRtcCam.Server;

/// <summary>
/// WebRTC のシグナリング(SDP / ICE の受け渡し)を中継する。映像そのものはここを通らない。
/// Android(sender)とブラウザ(viewer)を 1 組だけ想定し、届いたテキストをそのまま相手へ転送する。
/// </summary>
public sealed class SignalingRelay(ILogger<SignalingRelay> logger)
{
    private sealed class Peer(WebSocket socket, string role)
    {
        public WebSocket Socket { get; } = socket;
        public string Role { get; } = role;
        private readonly SemaphoreSlim _sendLock = new(1, 1);

        public async Task SendAsync(string text, CancellationToken ct)
        {
            if (Socket.State != WebSocketState.Open) return;
            await _sendLock.WaitAsync(ct);
            try { await Socket.SendAsync(Encoding.UTF8.GetBytes(text), WebSocketMessageType.Text, true, ct); }
            finally { _sendLock.Release(); }
        }
    }

    private readonly object _gate = new();
    private Peer? _sender;
    private Peer? _viewer;
    private bool _pairNotified; // 同じ組み合わせで peer-joined を二重に送らないため

    public async Task RunAsync(WebSocket socket, string role, CancellationToken ct)
    {
        var me = new Peer(socket, role);
        Peer? replaced;
        lock (_gate)
        {
            if (role == "sender") { replaced = _sender; _sender = me; }
            else { replaced = _viewer; _viewer = me; }
            _pairNotified = false; // どちらかが入れ替わったので、新しい組として扱う
        }
        logger.LogInformation("Signaling: {Role} connected", role);
        if (replaced is not null) await CloseQuietlyAsync(replaced);

        // 相手が揃ったら sender に知らせる。sender が offer を作って始まる。
        await NotifyPairIfReadyAsync(ct);

        var buffer = new byte[64 * 1024];
        try
        {
            while (socket.State == WebSocketState.Open && !ct.IsCancellationRequested)
            {
                using var ms = new MemoryStream();
                WebSocketReceiveResult result;
                do
                {
                    result = await socket.ReceiveAsync(buffer, ct);
                    if (result.MessageType == WebSocketMessageType.Close) return;
                    ms.Write(buffer, 0, result.Count);
                } while (!result.EndOfMessage);

                var text = Encoding.UTF8.GetString(ms.ToArray());
                var other = Other(role);
                if (other is not null) await other.SendAsync(text, ct);
            }
        }
        catch (Exception ex) when (ex is OperationCanceledException or WebSocketException) { }
        finally
        {
            Peer? other;
            lock (_gate)
            {
                if (role == "sender" && _sender == me) { _sender = null; _pairNotified = false; }
                if (role == "viewer" && _viewer == me) { _viewer = null; _pairNotified = false; }
                other = Other(role);
            }
            logger.LogInformation("Signaling: {Role} disconnected", role);
            if (other is not null)
                try { await other.SendAsync("{\"type\":\"peer-left\"}", CancellationToken.None); } catch { }
            await CloseQuietlyAsync(me);
        }
    }

    /// <summary>サーバー側から sender(Android)へメッセージを送る(AI / スクリプトからの画質変更用)。</summary>
    public async Task<bool> SendToSenderAsync(string text, CancellationToken ct)
    {
        Peer? sender;
        lock (_gate) sender = _sender;
        if (sender is null) return false;
        await sender.SendAsync(text, ct);
        return true;
    }

    private Peer? Other(string role)
    {
        lock (_gate) return role == "sender" ? _viewer : _sender;
    }

    private async Task NotifyPairIfReadyAsync(CancellationToken ct)
    {
        Peer? sender;
        lock (_gate)
        {
            sender = _viewer is not null && !_pairNotified ? _sender : null;
            if (sender is not null) _pairNotified = true;
        }
        if (sender is not null)
        {
            logger.LogInformation("Signaling: pair ready, asking sender to start");
            await sender.SendAsync("{\"type\":\"peer-joined\"}", ct);
        }
    }

    private static async Task CloseQuietlyAsync(Peer p)
    {
        try
        {
            if (p.Socket.State is WebSocketState.Open or WebSocketState.CloseReceived)
                await p.Socket.CloseAsync(WebSocketCloseStatus.NormalClosure, "bye", CancellationToken.None);
        }
        catch { }
    }
}