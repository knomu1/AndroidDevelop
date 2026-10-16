using System.Net.WebSockets;
using System.Text;
using System.Text.Json.Nodes;

namespace WebRtcCam.Server;

/// <summary>
/// WebRTC のシグナリング(SDP / ICE の受け渡し)を中継する。映像そのものはここを通らない。
/// Android(sender)は 1 台、ブラウザ(viewer)は複数。viewer ごとに ID を振り、sender との間で
///   viewer → sender : 届いたメッセージに viewerId を付けて転送
///   sender → viewer : viewerId があればその viewer だけへ、無ければ全 viewer へ転送
/// する。viewer の出入りは sender へ peer-joined / peer-left(viewerId 付き)で知らせる。
/// </summary>
public sealed class SignalingRelay(ILogger<SignalingRelay> logger)
{
    private sealed class Peer(WebSocket socket, string role, string id)
    {
        public WebSocket Socket { get; } = socket;
        public string Role { get; } = role;
        public string Id { get; } = id;
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
    private readonly Dictionary<string, Peer> _viewers = [];

    public int ViewerCount
    {
        get { lock (_gate) return _viewers.Count; }
    }

    public async Task RunAsync(WebSocket socket, string role, CancellationToken ct)
    {
        var me = new Peer(socket, role, Guid.NewGuid().ToString("N")[..8]);
        Peer? replacedSender = null;
        Peer[] existingViewers;
        lock (_gate)
        {
            if (role == "sender") { replacedSender = _sender; _sender = me; }
            else _viewers[me.Id] = me;
            existingViewers = [.. _viewers.Values];
        }
        logger.LogInformation("Signaling: {Role} {Id} connected (viewers={Count})", role, me.Id, ViewerCount);
        if (replacedSender is not null) await CloseQuietlyAsync(replacedSender);

        // sender に、接続してきた viewer(または既にいる全 viewer)を知らせる。sender が offer を作って始まる。
        if (role == "viewer")
            await TellSenderAsync("peer-joined", me.Id, ct);
        else
            foreach (var v in existingViewers) await TellSenderAsync("peer-joined", v.Id, ct);

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

                await ForwardAsync(me, Encoding.UTF8.GetString(ms.ToArray()), ct);
            }
        }
        catch (Exception ex) when (ex is OperationCanceledException or WebSocketException) { }
        finally
        {
            lock (_gate)
            {
                if (role == "sender" && _sender == me) _sender = null;
                if (role == "viewer") _viewers.Remove(me.Id);
            }
            logger.LogInformation("Signaling: {Role} {Id} disconnected (viewers={Count})", role, me.Id, ViewerCount);
            if (role == "viewer")
                try { await TellSenderAsync("peer-left", me.Id, CancellationToken.None); } catch { }
            else
                await BroadcastAsync("{\"type\":\"peer-left\"}", CancellationToken.None);
            await CloseQuietlyAsync(me);
        }
    }

    private async Task ForwardAsync(Peer from, string text, CancellationToken ct)
    {
        if (from.Role == "viewer")
        {
            // viewerId を付けて sender へ
            Peer? sender;
            lock (_gate) sender = _sender;
            if (sender is null) return;
            var node = JsonNode.Parse(text) as JsonObject;
            if (node is null) return;
            node["viewerId"] = from.Id;
            await sender.SendAsync(node.ToJsonString(), ct);
            return;
        }

        // sender → viewer
        var obj = JsonNode.Parse(text) as JsonObject;
        var target = obj?["viewerId"]?.GetValue<string>();
        if (target is null)
        {
            await BroadcastAsync(text, ct);
            return;
        }
        Peer? viewer;
        lock (_gate) _viewers.TryGetValue(target, out viewer);
        if (viewer is not null)
        {
            obj!.Remove("viewerId");
            await viewer.SendAsync(obj.ToJsonString(), ct);
        }
    }

    private async Task BroadcastAsync(string text, CancellationToken ct)
    {
        Peer[] viewers;
        lock (_gate) viewers = [.. _viewers.Values];
        foreach (var v in viewers)
            try { await v.SendAsync(text, ct); } catch { }
    }

    private async Task TellSenderAsync(string type, string viewerId, CancellationToken ct)
    {
        Peer? sender;
        lock (_gate) sender = _sender;
        if (sender is null) return;
        await sender.SendAsync($"{{\"type\":\"{type}\",\"viewerId\":\"{viewerId}\"}}", ct);
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
