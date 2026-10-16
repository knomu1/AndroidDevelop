namespace ScanMonitor.Contracts;

/// <summary>画像内の 1 点（ピクセル座標）。</summary>
public sealed record PointDto(float X, float Y);

/// <summary>画像内で検出された 1 つのバーコード。</summary>
public sealed record ScanCodeDto(string Value, string Format, IReadOnlyList<PointDto> Corners);

/// <summary>Android が画像と一緒に送るメタ情報（multipart の "metadata" パート）。</summary>
public sealed record ScanUploadMetadata(
    string DeviceId,
    DateTimeOffset CapturedAt,
    int ImageWidth,
    int ImageHeight,
    IReadOnlyList<ScanCodeDto> Codes);

/// <summary>サーバーが保持・配信する 1 件の受信データ。</summary>
public sealed record ScanRecord(
    Guid Id,
    string DeviceId,
    DateTimeOffset CapturedAt,
    DateTimeOffset ReceivedAt,
    int ImageWidth,
    int ImageHeight,
    string ImageUrl,
    IReadOnlyList<ScanCodeDto> Codes);

public static class ScanRoutes
{
    public const string Upload = "/api/scans";
    public const string Hub = "/hubs/scans";
    public const string ReceivedEvent = "ScanReceived";
    public const string Health = "/api/health";
    public const string Pairing = "/api/pairing";
    public const string PairingQr = "/api/pairing/qr.svg";
    public const string ApiKeyHeader = "X-Api-Key";
}

/// <summary>疎通確認用（認証不要）。</summary>
public sealed record HealthDto(string Name, string Version);

/// <summary>ペアリング情報。QR にもエンコードされる。</summary>
public sealed record PairingInfo(
    string Name,
    string Token,
    IReadOnlyList<string> Urls,
    string Payload);
