using Microsoft.AspNetCore.SignalR;

namespace ScanMonitor.Server;

/// <summary>画面への配信用ハブ。クライアントからの呼び出しはなく、サーバーからの push のみ。</summary>
public sealed class ScanHub(ILogger<ScanHub> logger) : Hub
{
    public override Task OnConnectedAsync()
    {
        logger.LogInformation("Viewer connected: {ConnectionId}", Context.ConnectionId);
        return base.OnConnectedAsync();
    }

    public override Task OnDisconnectedAsync(Exception? exception)
    {
        if (exception is null)
            logger.LogInformation("Viewer disconnected: {ConnectionId}", Context.ConnectionId);
        else
            logger.LogWarning(exception, "Viewer disconnected with error: {ConnectionId}", Context.ConnectionId);
        return base.OnDisconnectedAsync(exception);
    }
}
