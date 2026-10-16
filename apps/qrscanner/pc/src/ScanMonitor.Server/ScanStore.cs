using System.Collections.Concurrent;
using ScanMonitor.Contracts;

namespace ScanMonitor.Server;

/// <summary>受信したスキャンの保管。メタ情報は直近 N 件をメモリ、画像はディスクに保存する。</summary>
public sealed class ScanStore
{
    private const int MaxRecords = 500;

    private readonly ConcurrentQueue<ScanRecord> _records = new();
    private readonly string _imageDir;

    public ScanStore(IConfiguration config, IHostEnvironment env, ILogger<ScanStore> logger)
    {
        var dir = config["ScanMonitor:ImageDirectory"] ?? "data/images";
        _imageDir = Path.IsPathRooted(dir) ? dir : Path.GetFullPath(Path.Combine(env.ContentRootPath, dir));
        Directory.CreateDirectory(_imageDir);
        logger.LogInformation("Image directory: {ImageDirectory}", _imageDir);
    }

    /// <summary>新しい順。</summary>
    public IReadOnlyList<ScanRecord> Recent() => _records.Reverse().ToList();

    public async Task<ScanRecord> AddAsync(ScanUploadMetadata meta, Stream image, CancellationToken ct)
    {
        var id = Guid.NewGuid();
        await using (var file = File.Create(ImagePath(id)))
        {
            await image.CopyToAsync(file, ct);
        }

        var record = new ScanRecord(
            id, meta.DeviceId, meta.CapturedAt, DateTimeOffset.Now,
            meta.ImageWidth, meta.ImageHeight, $"/api/images/{id}", meta.Codes);

        _records.Enqueue(record);
        while (_records.Count > MaxRecords && _records.TryDequeue(out _)) { }
        return record;
    }

    public string? FindImage(Guid id)
    {
        var path = ImagePath(id);
        return File.Exists(path) ? path : null;
    }

    private string ImagePath(Guid id) => Path.Combine(_imageDir, $"{id}.jpg");
}
