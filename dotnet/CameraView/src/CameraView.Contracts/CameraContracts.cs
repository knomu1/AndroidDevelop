namespace CameraView.Contracts;

public static class CameraRoutes
{
    public const string Health = "/api/health";
    public const string Pairing = "/api/pairing";
    public const string PairingQr = "/api/pairing/qr";
    public const string Frames = "/api/frames";
    public const string Stream = "/api/stream";
    public const string Latest = "/api/frame.jpg";
    public const string Status = "/api/status";
    public const string Ocr = "/api/ocr";
    public const string ApiKeyHeader = "X-Api-Key";
}

public sealed record HealthDto(string Name, string Version);

/// <summary>Android ã«QRã§æ¸¡ã™æ¥ç¶šæƒ…å ±ã€‚Payload ãŒ QR ã®ä¸­èº«ã€‚</summary>
public sealed record PairingInfo(string Name, string Token, IReadOnlyList<string> Urls, string Payload);

public sealed record StatusDto(
    bool Receiving,
    string? DeviceId,
    int Width,
    int Height,
    long TotalFrames,
    double Fps,
    DateTimeOffset? LastFrameAt,
    long LastFrameBytes);

public sealed record PointDto(int X, int Y);

/// <summary>”F¯‚µ‚½ 1 sBCorners ‚Í‰æ‘œƒsƒNƒZƒ‹À•W‚Ì 4 “_(¶ã‚©‚çŒv‰ñ‚è)B</summary>
public sealed record OcrItem(string Text, IReadOnlyList<PointDto> Corners);

/// <summary>Android ‚©‚ç“Í‚­”F¯Œ‹‰ÊBÀ•W‚Í ImageWidth x ImageHeight ‚Ì‰æ‘œã‚ÌƒsƒNƒZƒ‹B</summary>
public sealed record OcrUpload(string? DeviceId, int ImageWidth, int ImageHeight, IReadOnlyList<OcrItem> Items);

/// <summary>Web ‰æ–Ê‚Ö•Ô‚·”F¯Œ‹‰ÊBAgeMs ‚ÍóM‚©‚ç‚ÌŒo‰ßŠÔ(ŒÃ‚¢Œ‹‰Ê‚Ì˜g‚ğÁ‚·‚Ì‚Ég‚¤)B</summary>
public sealed record OcrResultDto(
    string? DeviceId, int ImageWidth, int ImageHeight, IReadOnlyList<OcrItem> Items, long Sequence, long AgeMs);
