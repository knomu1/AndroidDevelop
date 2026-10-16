using System.Text.Json;
using Serilog;
using WebRtcCam.Contracts;
using WebRtcCam.Server;

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

    builder.Services.AddSingleton<PairingService>();
    builder.Services.AddSingleton<SignalingRelay>();

    var app = builder.Build();

    app.UseSerilogRequestLogging(o =>
        o.GetLevel = (ctx, _, ex) => ex is not null || ctx.Response.StatusCode >= 500 ? Serilog.Events.LogEventLevel.Error
            : ctx.Request.Path.StartsWithSegments("/api") ? Serilog.Events.LogEventLevel.Debug
            : Serilog.Events.LogEventLevel.Information);

    var noCacheIndex = new StaticFileOptions
    {
        OnPrepareResponse = ctx =>
        {
            var name = ctx.File.Name;
            if (name.Equals("index.html", StringComparison.OrdinalIgnoreCase) || name.EndsWith(".js", StringComparison.OrdinalIgnoreCase))
                ctx.Context.Response.Headers.CacheControl = "no-cache";
        },
    };

    app.UseWebSockets();
    app.UseBlazorFrameworkFiles();
    app.UseStaticFiles(noCacheIndex);

    app.MapGet(WebRtcRoutes.Health, (PairingService pairing) => new HealthDto(pairing.ServerName, "1"));

    app.MapGet(WebRtcRoutes.Pairing, (HttpRequest request, PairingService pairing) =>
        pairing.Build(request.Host.Port ?? 5081));

    app.MapGet(WebRtcRoutes.PairingQr, (HttpRequest request, PairingService pairing) =>
    {
        var info = pairing.Build(request.Host.Port ?? 5081);
        return Results.Content(pairing.RenderQrSvg(info.Payload), "image/svg+xml");
    });

    // 別の PC / iPad でこの画面を開くための URL の QR
    app.MapGet(WebRtcRoutes.ViewerQr, (HttpRequest request, PairingService pairing) =>
    {
        var url = pairing.Build(request.Host.Port ?? 5081).Urls.FirstOrDefault() ?? $"http://{request.Host}";
        return Results.Content(pairing.RenderQrSvg(url + "/"), "image/svg+xml");
    });

    // シグナリング: sender(Android)はトークン必須、viewer(ブラウザ)は不要(LAN 内の検証用)
    app.Map(WebRtcRoutes.Signaling, async (HttpContext http, SignalingRelay relay, PairingService pairing, ILogger<Program> logger) =>
    {
        if (!http.WebSockets.IsWebSocketRequest) { http.Response.StatusCode = 400; return; }
        var role = http.Request.Query["role"].ToString();
        if (role is not ("sender" or "viewer")) { http.Response.StatusCode = 400; return; }
        if (role == "sender" && !pairing.IsValidToken(http.Request.Headers[WebRtcRoutes.ApiKeyHeader].ToString()))
        {
            logger.LogWarning("Rejected sender: missing or invalid token from {RemoteIp}", http.Connection.RemoteIpAddress);
            http.Response.StatusCode = 401;
            return;
        }
        using var socket = await http.WebSockets.AcceptWebSocketAsync();
        await relay.RunAsync(socket, role, http.RequestAborted);
    });

    // ブラウザが計測した受信統計(解像度・fps・ビットレート等)。画面表示とは別に、直近の値をログ・取得用に保持する。
    JsonElement? lastViewerStats = null;
    var lastViewerStatsAt = DateTimeOffset.MinValue;
    var lastLogged = DateTimeOffset.MinValue;
    app.MapPost(WebRtcRoutes.ViewerStats, (JsonElement stats, ILogger<Program> logger) =>
    {
        lastViewerStats = stats.Clone();
        lastViewerStatsAt = DateTimeOffset.UtcNow;
        if (lastViewerStatsAt - lastLogged > TimeSpan.FromSeconds(5))
        {
            lastLogged = lastViewerStatsAt;
            logger.LogInformation("Viewer stats: {Stats}", stats.GetRawText());
        }
        return Results.NoContent();
    });
    app.MapGet(WebRtcRoutes.ViewerStats, () => lastViewerStats is { } s
        ? Results.Json(new { ageMs = (long)(DateTimeOffset.UtcNow - lastViewerStatsAt).TotalMilliseconds, stats = s })
        : Results.NoContent());

    // 画質設定を API から変更する(ブラウザの操作と同じ config メッセージを Android へ送る)。例:
    //   POST /api/config {"width":1920,"height":1080,"fps":30,"bitrateKbps":10000,"codec":"H264"}
    app.MapPost("/api/config", async (JsonElement config, SignalingRelay relay, CancellationToken ct) =>
    {
        var json = JsonSerializer.Serialize(new Dictionary<string, object?>(
            config.EnumerateObject().Select(p => new KeyValuePair<string, object?>(p.Name, p.Value))) { ["type"] = "config" });
        return await relay.SendToSenderAsync(json, ct) ? Results.Accepted() : Results.Conflict("sender is not connected");
    });

    app.MapFallbackToFile("index.html", noCacheIndex);

    app.Lifetime.ApplicationStarted.Register(() =>
    {
        Log.Information("WebRtcCam started. Listening on {Urls}", string.Join(", ", app.Urls));
        var pairing = app.Services.GetRequiredService<PairingService>();
        foreach (var url in pairing.Build(5081).Urls)
            Log.Information("Open from other devices on the LAN: {Url}/", url);
    });

    app.Run();
}
catch (Exception ex) when (ex is not HostAbortedException)
{
    Log.Fatal(ex, "WebRtcCam terminated unexpectedly");
}
finally
{
    Log.CloseAndFlush();
}