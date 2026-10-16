using Serilog;
using CameraView.Contracts;
using CameraView.Server;

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

    builder.Services.AddSingleton<FrameStore>();
    builder.Services.AddSingleton<PairingService>();
    builder.Services.AddSingleton<OcrStore>();

    var app = builder.Build();

    // 高頻度のエンドポイントは Debug に落として、ログを埋めないようにする
    app.UseSerilogRequestLogging(o =>
        o.GetLevel = (ctx, _, ex) => ex is not null || ctx.Response.StatusCode >= 500 ? Serilog.Events.LogEventLevel.Error
            : ctx.Request.Path.StartsWithSegments("/api") ? Serilog.Events.LogEventLevel.Debug
            : Serilog.Events.LogEventLevel.Information);

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

    app.MapGet(CameraRoutes.Health, (PairingService pairing) => new HealthDto(pairing.ServerName, "1"));

    app.MapGet(CameraRoutes.Pairing, (HttpRequest request, PairingService pairing) =>
        pairing.Build(request.Host.Port ?? 5080));

    app.MapGet(CameraRoutes.PairingQr, (HttpRequest request, PairingService pairing) =>
    {
        var info = pairing.Build(request.Host.Port ?? 5080);
        return Results.Content(pairing.RenderQrSvg(info.Payload), "image/svg+xml");
    });

    // Android からのフレーム受信: body = JPEG
    app.MapPost(CameraRoutes.Frames, async (
        HttpRequest request, FrameStore store, PairingService pairing, ILogger<Program> logger, CancellationToken ct) =>
    {
        if (!pairing.IsValidToken(request.Headers[CameraRoutes.ApiKeyHeader].ToString()))
        {
            logger.LogWarning("Rejected frame: missing or invalid {Header} from {RemoteIp}",
                CameraRoutes.ApiKeyHeader, request.HttpContext.Connection.RemoteIpAddress);
            return Results.Unauthorized();
        }

        using var ms = new MemoryStream();
        await request.Body.CopyToAsync(ms, ct);
        var jpeg = ms.ToArray();
        if (jpeg.Length < 4 || jpeg[0] != 0xFF || jpeg[1] != 0xD8)
            return Results.BadRequest("JPEG が必要です");

        _ = int.TryParse(request.Query["w"], out var w);
        _ = int.TryParse(request.Query["h"], out var h);
        var device = request.Query["device"].ToString();

        var before = store.Status().TotalFrames;
        store.Set(jpeg, device, w, h);
        if (before == 0)
            logger.LogInformation("First frame received from {Device} ({Width}x{Height}, {Bytes}B) at {RemoteIp}",
                device, w, h, jpeg.Length, request.HttpContext.Connection.RemoteIpAddress);
        return Results.NoContent();
    }).DisableAntiforgery();

    app.MapGet(CameraRoutes.Latest, (FrameStore store) =>
        store.Latest().Jpeg is { } jpeg ? Results.File(jpeg, "image/jpeg") : Results.NotFound());
    // Android からの文字認識結果(JSON)
    app.MapPost(CameraRoutes.Ocr, (
        OcrUpload upload, HttpRequest request, OcrStore store, PairingService pairing, ILogger<Program> logger) =>
    {
        if (!pairing.IsValidToken(request.Headers[CameraRoutes.ApiKeyHeader].ToString()))
        {
            logger.LogWarning("Rejected OCR: missing or invalid {Header}", CameraRoutes.ApiKeyHeader);
            return Results.Unauthorized();
        }
        if (upload.ImageWidth <= 0 || upload.ImageHeight <= 0 || upload.Items is null)
            return Results.BadRequest("imageWidth, imageHeight, items が必要です");

        store.Set(upload);
        if (upload.Items.Count > 0)
            logger.LogInformation("OCR {Count} lines: {Text}", upload.Items.Count,
                string.Join(" | ", upload.Items.Select(i => i.Text)));
        return Results.NoContent();
    });

    app.MapGet(CameraRoutes.Ocr, (OcrStore store) => store.Latest() is { } r ? Results.Ok(r) : Results.NoContent());

    app.MapGet(CameraRoutes.Status, (FrameStore store) => store.Status());

    // MJPEG ストリーム: <img src="/api/stream"> でそのまま表示できる
    app.MapGet(CameraRoutes.Stream, async (HttpContext http, FrameStore store, CancellationToken ct) =>
    {
        const string boundary = "frame";
        http.Response.ContentType = $"multipart/x-mixed-replace; boundary={boundary}";
        http.Response.Headers.CacheControl = "no-store";
        var lastSeq = 0L;
        try
        {
            while (!ct.IsCancellationRequested)
            {
                var (jpeg, seq) = await store.NextAsync(lastSeq, ct);
                lastSeq = seq;
                var head = System.Text.Encoding.ASCII.GetBytes(
                    $"--{boundary}\r\nContent-Type: image/jpeg\r\nContent-Length: {jpeg.Length}\r\n\r\n");
                await http.Response.Body.WriteAsync(head, ct);
                await http.Response.Body.WriteAsync(jpeg, ct);
                await http.Response.Body.WriteAsync("\r\n"u8.ToArray(), ct);
                await http.Response.Body.FlushAsync(ct);
            }
        }
        catch (OperationCanceledException) { }
    });

    app.MapFallbackToFile("index.html", noCacheIndex);

    app.Lifetime.ApplicationStarted.Register(() =>
        Log.Information("CameraView started. Listening on {Urls}", string.Join(", ", app.Urls)));

    app.Run();
}
catch (Exception ex) when (ex is not HostAbortedException)
{
    Log.Fatal(ex, "CameraView terminated unexpectedly");
}
finally
{
    Log.CloseAndFlush();
}

