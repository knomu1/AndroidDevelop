using System.Text.Json;
using Microsoft.AspNetCore.SignalR;
using Serilog;
using ScanMonitor.Contracts;
using ScanMonitor.Server;

Log.Logger = new LoggerConfiguration()
    .WriteTo.Console()
    .CreateBootstrapLogger();

try
{
    var builder = WebApplication.CreateBuilder(args);

    builder.Host.UseSerilog((ctx, services, cfg) => cfg
        .ReadFrom.Configuration(ctx.Configuration)
        .ReadFrom.Services(services)
        .Enrich.FromLogContext());

    builder.Services.AddSignalR();
    builder.Services.AddSingleton<ScanStore>();
    builder.Services.AddSingleton<PairingService>();

    var app = builder.Build();

    // 1 リクエスト 1 行のアクセスログ（Serilog）
    app.UseSerilogRequestLogging(o =>
        o.GetLevel = (ctx, _, ex) => ex is not null || ctx.Response.StatusCode >= 500 ? Serilog.Events.LogEventLevel.Error
            : ctx.Request.Path.StartsWithSegments("/api/pairing") || ctx.Request.Path.StartsWithSegments("/api/health")
                ? Serilog.Events.LogEventLevel.Debug
                : Serilog.Events.LogEventLevel.Information);

    // index.html は再ビルドでファイル名のハッシュが変わるため、常に再検証させる（古いキャッシュによる 404 防止）
    var noCacheIndex = new StaticFileOptions
    {
        OnPrepareResponse = ctx =>
        {
            if (ctx.File.Name.Equals("index.html", StringComparison.OrdinalIgnoreCase))
                ctx.Context.Response.Headers.CacheControl = "no-cache";
        },
    };

    app.UseBlazorFrameworkFiles();
    app.UseStaticFiles(noCacheIndex);

    app.MapHub<ScanHub>(ScanRoutes.Hub);

    // Android からの受信: multipart/form-data { metadata: JSON, image: JPEG }
    app.MapPost(ScanRoutes.Upload, async (
        HttpRequest request,
        ScanStore store,
        PairingService pairing,
        IHubContext<ScanHub> hub,
        ILogger<Program> logger,
        CancellationToken ct) =>
    {
        if (!pairing.IsValidToken(request.Headers[ScanRoutes.ApiKeyHeader].ToString()))
        {
            logger.LogWarning("Rejected upload: missing or invalid {Header} from {RemoteIp}",
                ScanRoutes.ApiKeyHeader, request.HttpContext.Connection.RemoteIpAddress);
            return Results.Unauthorized();
        }

        if (!request.HasFormContentType)
        {
            logger.LogWarning("Rejected upload: content type {ContentType} is not multipart/form-data", request.ContentType);
            return Results.BadRequest("multipart/form-data が必要です");
        }

        var form = await request.ReadFormAsync(ct);
        var image = form.Files["image"];
        var metadataJson = form["metadata"].ToString();

        if (image is null || image.Length == 0 || string.IsNullOrWhiteSpace(metadataJson))
        {
            logger.LogWarning("Rejected upload: missing part (image={HasImage}, metadata={HasMetadata})",
                image is { Length: > 0 }, !string.IsNullOrWhiteSpace(metadataJson));
            return Results.BadRequest("image と metadata が必要です");
        }

        ScanUploadMetadata? meta;
        try
        {
            meta = JsonSerializer.Deserialize<ScanUploadMetadata>(metadataJson, JsonSerializerOptions.Web);
        }
        catch (JsonException ex)
        {
            logger.LogWarning("Rejected upload: metadata is not valid JSON ({Reason})", ex.Message);
            return Results.BadRequest("metadata の JSON が不正です");
        }

        if (meta is null || meta.Codes is null || meta.Codes.Count == 0)
        {
            logger.LogWarning("Rejected upload: metadata has no codes");
            return Results.BadRequest("codes が空です");
        }

        logger.LogInformation(
            "Scan received: device={DeviceId} codes={CodeCount} image={ImageBytes}B {Width}x{Height}",
            meta.DeviceId, meta.Codes.Count, image.Length, meta.ImageWidth, meta.ImageHeight);

        await using var stream = image.OpenReadStream();
        var record = await store.AddAsync(meta, stream, ct);

        foreach (var code in record.Codes)
            logger.LogDebug("  code [{Format}] {Value}", code.Format, code.Value);

        await hub.Clients.All.SendAsync(ScanRoutes.ReceivedEvent, record, ct);
        logger.LogInformation("Scan {ScanId} stored and broadcast", record.Id);

        return Results.Created($"/api/scans/{record.Id}", record);
    }).DisableAntiforgery();

    // 画面の初期表示用: 直近の受信一覧
    app.MapGet(ScanRoutes.Upload, (ScanStore store) => store.Recent());

    // 疎通確認（認証不要）: Android がペアリング時に接続候補へ順に問い合わせる
    app.MapGet(ScanRoutes.Health, (HttpRequest request, PairingService pairing, ILogger<Program> logger) =>
    {
        logger.LogDebug("Health check from {RemoteIp}", request.HttpContext.Connection.RemoteIpAddress);
        return new HealthDto(pairing.ServerName, "1");
    });

    // ペアリング情報（画面の QR 表示用）
    app.MapGet(ScanRoutes.Pairing, (HttpRequest request, PairingService pairing) =>
        pairing.Build(request.Host.Port ?? 5080));

    app.MapGet(ScanRoutes.PairingQr, (HttpRequest request, PairingService pairing) =>
    {
        var info = pairing.Build(request.Host.Port ?? 5080);
        return Results.Content(pairing.RenderQrSvg(info.Payload), "image/svg+xml");
    });

    app.MapGet("/api/images/{id:guid}", (Guid id, ScanStore store, ILogger<Program> logger) =>
    {
        var path = store.FindImage(id);
        if (path is null)
        {
            logger.LogWarning("Image not found: {ImageId}", id);
            return Results.NotFound();
        }
        return Results.File(path, "image/jpeg");
    });

    app.MapFallbackToFile("index.html", noCacheIndex);

    app.Lifetime.ApplicationStarted.Register(() =>
        Log.Information("ScanMonitor started. Listening on {Urls}", string.Join(", ", app.Urls)));

    app.Run();
}
catch (Exception ex) when (ex is not HostAbortedException)
{
    Log.Fatal(ex, "ScanMonitor terminated unexpectedly");
}
finally
{
    Log.CloseAndFlush();
}
