namespace WebRtcCam.Contracts;

public static class WebRtcRoutes
{
    public const string Health = "/api/health";
    public const string Pairing = "/api/pairing";
    public const string PairingQr = "/api/pairing/qr";
    /// <summary>他の端末(別 PC / iPad)で開く画面の URL を QR にしたもの(SVG)。</summary>
    public const string ViewerQr = "/api/viewer-qr";
    public const string ViewerStats = "/api/viewer-stats";
    /// <summary>シグナリング用 WebSocket。?role=sender（Android・要トークン）/ role=viewer（ブラウザ）</summary>
    public const string Signaling = "/ws";
    public const string ApiKeyHeader = "X-Api-Key";
}

public sealed record HealthDto(string Name, string Version);

/// <summary>Android にQRで渡す接続情報。Payload が QR の中身。</summary>
public sealed record PairingInfo(string Name, string Token, IReadOnlyList<string> Urls, string Payload);