using CameraView.Contracts;

namespace CameraView.Server;

/// <summary>直近の文字認識結果を 1 件だけ保持する。</summary>
public sealed class OcrStore
{
    private readonly object _gate = new();
    private OcrUpload? _latest;
    private long _seq;
    private DateTimeOffset _at;

    public void Set(OcrUpload upload)
    {
        lock (_gate)
        {
            _latest = upload;
            _seq++;
            _at = DateTimeOffset.UtcNow;
        }
    }

    public OcrResultDto? Latest()
    {
        lock (_gate)
        {
            if (_latest is null) return null;
            return new OcrResultDto(_latest.DeviceId, _latest.ImageWidth, _latest.ImageHeight,
                _latest.Items, _seq, (long)(DateTimeOffset.UtcNow - _at).TotalMilliseconds);
        }
    }
}