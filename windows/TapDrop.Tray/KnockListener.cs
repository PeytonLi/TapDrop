using System.Buffers.Binary;
using NAudio.Wave;
using TapDrop.Core;

namespace TapDrop.Tray;

internal sealed class KnockListener : IDisposable
{
    private readonly KnockDetector _detector;
    private WaveInEvent? _waveIn;

    public KnockListener(KnockConfig? config = null)
    {
        _detector = new KnockDetector(16000, config);
    }

    public event Action<KnockEvent>? Transient;
    public event Action<KnockEvent>? Triggered;
    public event Action<double, double>? Level;

    public KnockConfig Config
    {
        get => _detector.Config;
        set => _detector.Config = value;
    }

    public bool Start(out string error)
    {
        error = "";
        if (_waveIn is not null) return true;
        if (WaveInEvent.DeviceCount == 0)
        {
            error = "No microphone available";
            return false;
        }

        WaveInEvent? waveIn = null;
        try
        {
            waveIn = new WaveInEvent
            {
                DeviceNumber = -1,
                WaveFormat = new WaveFormat(16000, 16, 1),
                BufferMilliseconds = 50,
            };
            waveIn.DataAvailable += OnDataAvailable;
            waveIn.StartRecording();
            _waveIn = waveIn;
            return true;
        }
        catch (Exception exception)
        {
            if (waveIn is not null)
            {
                waveIn.DataAvailable -= OnDataAvailable;
                waveIn.Dispose();
            }
            error = $"No microphone available: {exception.Message}";
            return false;
        }
    }

    public void Stop()
    {
        var waveIn = _waveIn;
        _waveIn = null;
        if (waveIn is null) return;
        waveIn.DataAvailable -= OnDataAvailable;
        try { waveIn.StopRecording(); }
        catch { }
        waveIn.Dispose();
    }

    public void Dispose() => Stop();

    private void OnDataAvailable(object? sender, WaveInEventArgs eventArgs)
    {
        var count = eventArgs.BytesRecorded / sizeof(short);
        var samples = new float[count];
        var peak = 0f;
        for (var index = 0; index < count; index++)
        {
            var value = BinaryPrimitives.ReadInt16LittleEndian(eventArgs.Buffer.AsSpan(index * sizeof(short), sizeof(short)));
            samples[index] = value / 32768f;
            peak = Math.Max(peak, Math.Abs(samples[index]));
        }

        var events = _detector.Process(samples);
        Level?.Invoke(peak > 0 ? Math.Max(-100, 20 * Math.Log10(peak)) : -100, _detector.NoiseFloorDbfs);
        foreach (var knockEvent in events)
        {
            if (knockEvent.Kind == KnockEventKind.Transient) Transient?.Invoke(knockEvent);
            else Triggered?.Invoke(knockEvent);
        }
    }
}
