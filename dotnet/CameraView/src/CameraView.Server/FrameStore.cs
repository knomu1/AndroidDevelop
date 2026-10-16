using CameraView.Contracts;

namespace CameraView.Server;

/// <summary>最新フレームを保持し、MJPEG 配信側へ新着を通知する。</summary>
public sealed class FrameStore
{
    private readonly object _gate = new();
    private readonly Queue<DateTimeOffset> _recent = new();
    private TaskCompletionSource _signal = NewSignal();
    private byte[]? _latest;
    private long _seq;
    private string? _deviceId;
    private int _width, _height;
    private DateTimeOffset? _lastAt;

    private static TaskCompletionSource NewSignal() => new(TaskCreationOptions.RunContinuationsAsynchronously);

    public void Set(byte[] jpeg, string? deviceId, int width, int height)
    {
        TaskCompletionSource old;
        lock (_gate)
        {
            var now = DateTimeOffset.UtcNow;
            _latest = jpeg;
            _seq++;
            _deviceId = deviceId;
            _width = width;
            _height = height;
            _lastAt = now;
            _recent.Enqueue(now);
            while (_recent.Count > 0 && now - _recent.Peek() > TimeSpan.FromSeconds(3)) _recent.Dequeue();
            old = _signal;
            _signal = NewSignal();
        }
        old.TrySetResult();
    }

    public (byte[]? Jpeg, long Seq) Latest()
    {
        lock (_gate) return (_latest, _seq);
    }

    /// <summary>lastSeq より新しいフレームが来るまで待つ。</summary>
    public async Task<(byte[] Jpeg, long Seq)> NextAsync(long lastSeq, CancellationToken ct)
    {
        while (true)
        {
            Task wait;
            lock (_gate)
            {
                if (_latest is not null && _seq > lastSeq) return (_latest, _seq);
                wait = _signal.Task;
            }
            await wait.WaitAsync(ct);
        }
    }

    public StatusDto Status()
    {
        lock (_gate)
        {
            var now = DateTimeOffset.UtcNow;
            var window = _recent.Where(t => now - t <= TimeSpan.FromSeconds(3)).ToList();
            var fps = window.Count >= 2 ? (window.Count - 1) / (window[^1] - window[0]).TotalSeconds : 0;
            var receiving = _lastAt is { } at && now - at < TimeSpan.FromSeconds(3);
            return new StatusDto(receiving, _deviceId, _width, _height, _seq, receiving ? fps : 0, _lastAt, _latest?.LongLength ?? 0);
        }
    }
}
