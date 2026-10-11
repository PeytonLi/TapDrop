namespace TapDrop.Core;

public sealed record KnockConfig
{
    public double ThresholdDb { get; init; }
    public double MinLevelDbfs { get; init; }
    public double AttackDb { get; init; }
    public double MinCrestFactor { get; init; }
    public double MaxTransientMs { get; init; } = 50;
    public double DecayDb { get; init; }
    public double RefractoryMs { get; init; } = 80;
    public double MinGapMs { get; init; } = 120;
    public double MaxGapMs { get; init; } = 600;
    public double TrailingSilenceMs { get; init; } = 400;
    public double CooldownMs { get; init; } = 2000;

    public static KnockConfig Default { get; } = new()
    {
        ThresholdDb = 14,
        MinLevelDbfs = -25,
        AttackDb = 9,
        MinCrestFactor = 2.8,
        DecayDb = 8,
    };

    public static KnockConfig FromSensitivity(int sensitivity)
    {
        sensitivity = Math.Clamp(sensitivity, 1, 10);
        var adjustment = (5 - sensitivity) * 1.5;
        return Default with
        {
            ThresholdDb = Default.ThresholdDb + adjustment,
            MinLevelDbfs = Default.MinLevelDbfs + adjustment,
        };
    }
}

public enum KnockEventKind { Transient, Trigger }

public sealed record KnockEvent(
    KnockEventKind Kind,
    double TimeSeconds,
    double LevelDbfs,
    double RatioDb,
    double CrestFactor);

public sealed class KnockDetector
{
    private readonly int _sampleRate;
    private readonly int _windowSamples;
    private readonly int _crestSamples;
    private readonly double[] _previousPowers = new double[4];
    private KnockConfig _config;
    private int _windowFill;
    private double _windowPower;
    private double _windowPeak;
    private long _samplesProcessed;
    private int _previousPowerCount;
    private int _previousPowerIndex;
    private double _noiseFloorDbfs = -80;
    private bool _hasNoiseFloor;
    private Candidate? _candidate;
    private long _lastTransientSample = long.MinValue;
    private Sequence? _sequence;
    private long _cooldownEndSample;
    private double _currentLevelDbfs = -100;

    public KnockDetector(int sampleRate, KnockConfig? config = null)
    {
        if (sampleRate is < 8000 or > 192000)
            throw new ArgumentOutOfRangeException(nameof(sampleRate), "Sample rate must be between 8000 and 192000 Hz.");
        _sampleRate = sampleRate;
        _windowSamples = Math.Max(1, (int)Math.Round(sampleRate * 0.005));
        _crestSamples = Math.Max(1, (int)Math.Round(sampleRate * 0.05));
        _config = config ?? KnockConfig.Default;
    }

    public KnockConfig Config
    {
        get => _config;
        set => _config = value ?? throw new ArgumentNullException(nameof(value));
    }

    public double CurrentLevelDbfs => _currentLevelDbfs;
    public double NoiseFloorDbfs => _noiseFloorDbfs;

    public IReadOnlyList<KnockEvent> Process(ReadOnlySpan<float> samples)
    {
        List<KnockEvent>? events = null;
        foreach (var sample in samples)
        {
            var value = float.IsFinite(sample) ? sample : 0;
            var square = (double)value * value;
            _windowPower += square;
            _windowPeak = Math.Max(_windowPeak, Math.Abs(value));
            if (_candidate is { CrestSampleCount: var count } candidate && count < _crestSamples)
            {
                candidate.CrestPower += square;
                candidate.CrestSampleCount++;
            }
            _windowFill++;
            _samplesProcessed++;

            if (_windowFill == _windowSamples)
            {
                events ??= [];
                AnalyzeWindow(events);
            }
        }
        return events ?? (IReadOnlyList<KnockEvent>)Array.Empty<KnockEvent>();
    }

    public void Reset()
    {
        Array.Clear(_previousPowers);
        _windowFill = 0;
        _windowPower = 0;
        _windowPeak = 0;
        _samplesProcessed = 0;
        _previousPowerCount = 0;
        _previousPowerIndex = 0;
        _noiseFloorDbfs = -80;
        _hasNoiseFloor = false;
        _candidate = null;
        _lastTransientSample = long.MinValue;
        _sequence = null;
        _cooldownEndSample = 0;
        _currentLevelDbfs = -100;
    }

    private void AnalyzeWindow(List<KnockEvent> events)
    {
        var windowStart = _samplesProcessed - _windowSamples;
        var meanPower = _windowPower / _windowSamples;
        var levelDbfs = ToDbfs(meanPower);
        _currentLevelDbfs = levelDbfs;

        if (!_hasNoiseFloor)
        {
            _noiseFloorDbfs = Math.Max(-80, levelDbfs);
            _hasNoiseFloor = true;
        }

        MaybeFinishSequence(windowStart, events);
        var floorBeforeUpdate = _noiseFloorDbfs;
        var ratioDb = levelDbfs - floorBeforeUpdate;
        var previousMeanPower = PreviousMeanPower();
        var attackDb = levelDbfs - ToDbfs(previousMeanPower);

        if (_candidate is { } candidate)
        {
            if (levelDbfs > candidate.PeakDbfs && !candidate.SawDecay)
                candidate.PeakDbfs = levelDbfs;
            var elapsedSamples = _samplesProcessed - candidate.OnsetSample;
            if (elapsedSamples <= MillisecondsToSamples(_config.MaxTransientMs)
                && levelDbfs <= candidate.PeakDbfs - _config.DecayDb)
                candidate.SawDecay = true;

            if (candidate.SawDecay && candidate.CrestSampleCount >= _crestSamples)
            {
                var rms = Math.Sqrt(candidate.CrestPower / _crestSamples);
                var crestFactor = candidate.OnsetPeak / Math.Max(rms, 1e-12);
                _candidate = null;
                if (crestFactor >= _config.MinCrestFactor)
                    AcceptTransient(candidate, crestFactor, events);
            }
            else if (elapsedSamples > Math.Max(_windowSamples, MillisecondsToSamples(_config.MaxTransientMs)))
            {
                _candidate = null;
            }
        }
        else if (IsOutsideRefractory(windowStart)
            && ratioDb >= _config.ThresholdDb
            && levelDbfs >= _config.MinLevelDbfs
            && attackDb >= _config.AttackDb)
        {
            var newCandidate = new Candidate(
                windowStart,
                levelDbfs,
                ratioDb,
                _windowPeak,
                _windowPower,
                _windowSamples);
            if (levelDbfs <= newCandidate.PeakDbfs - _config.DecayDb)
                newCandidate.SawDecay = true;
            _candidate = newCandidate;
        }

        AddPreviousPower(meanPower);
        var tauSeconds = levelDbfs < _noiseFloorDbfs ? 0.05 : 1.0;
        var alpha = 1 - Math.Exp(-_windowSamples / (double)_sampleRate / tauSeconds);
        _noiseFloorDbfs = Math.Max(-80, _noiseFloorDbfs + alpha * (levelDbfs - _noiseFloorDbfs));
        MaybeFinishSequence(_samplesProcessed, events);

        _windowFill = 0;
        _windowPower = 0;
        _windowPeak = 0;
    }

    private void AcceptTransient(Candidate candidate, double crestFactor, List<KnockEvent> events)
    {
        _lastTransientSample = candidate.OnsetSample;
        events.Add(new KnockEvent(
            KnockEventKind.Transient,
            candidate.OnsetSample / (double)_sampleRate,
            candidate.LevelDbfs,
            candidate.RatioDb,
            crestFactor));

        if (candidate.OnsetSample < _cooldownEndSample) return;
        if (_cooldownEndSample > 0)
        {
            _sequence = null;
            _cooldownEndSample = 0;
        }

        if (_sequence is null)
        {
            _sequence = new Sequence(candidate.OnsetSample, candidate.OnsetSample, 1, candidate);
            return;
        }

        var gapSamples = candidate.OnsetSample - _sequence.LastSample;
        var gapMs = gapSamples * 1000.0 / _sampleRate;
        if (gapMs > _config.MaxGapMs)
        {
            _sequence = new Sequence(candidate.OnsetSample, candidate.OnsetSample, 1, candidate);
            return;
        }

        if (_sequence.Count >= 2 || gapMs < _config.MinGapMs || _sequence.Poisoned)
        {
            _sequence.Count++;
            _sequence.Poisoned = true;
            _sequence.LastSample = candidate.OnsetSample;
            return;
        }

        _sequence.Count = 2;
        _sequence.LastSample = candidate.OnsetSample;
        _sequence.SecondCandidate = candidate;
        _sequence.SecondCrestFactor = crestFactor;
    }

    private void MaybeFinishSequence(long sample, List<KnockEvent> events)
    {
        var sequence = _sequence;
        if (sequence is null || sequence.Count != 2 || sequence.Poisoned) return;

        var deadline = sequence.LastSample + MillisecondsToSamples(_config.TrailingSilenceMs);
        if (sample < deadline) return;
        if (_candidate is { } candidate && candidate.OnsetSample <= deadline) return;

        var gapMs = (sequence.LastSample - sequence.FirstSample) * 1000.0 / _sampleRate;
        if (gapMs >= _config.MinGapMs && gapMs <= _config.MaxGapMs)
        {
            var second = sequence.SecondCandidate;
            events.Add(new KnockEvent(
                KnockEventKind.Trigger,
                deadline / (double)_sampleRate,
                second.LevelDbfs,
                second.RatioDb,
                sequence.SecondCrestFactor));
            _cooldownEndSample = deadline + MillisecondsToSamples(_config.CooldownMs);
        }
        _sequence = null;
    }

    private bool IsOutsideRefractory(long onsetSample) =>
        _lastTransientSample == long.MinValue
        || onsetSample - _lastTransientSample >= MillisecondsToSamples(_config.RefractoryMs);

    private long MillisecondsToSamples(double milliseconds) =>
        (long)Math.Round(_sampleRate * milliseconds / 1000);

    private double PreviousMeanPower()
    {
        if (_previousPowerCount == 0) return 0;
        var sum = 0.0;
        for (var index = 0; index < _previousPowerCount; index++) sum += _previousPowers[index];
        return sum / _previousPowerCount;
    }

    private void AddPreviousPower(double power)
    {
        _previousPowers[_previousPowerIndex] = power;
        _previousPowerIndex = (_previousPowerIndex + 1) % _previousPowers.Length;
        _previousPowerCount = Math.Min(_previousPowerCount + 1, _previousPowers.Length);
    }

    private static double ToDbfs(double power) =>
        power <= 1e-10 ? -100 : Math.Max(-100, 10 * Math.Log10(power));

    private sealed class Candidate(
        long onsetSample,
        double levelDbfs,
        double ratioDb,
        double onsetPeak,
        double crestPower,
        int crestSampleCount)
    {
        public long OnsetSample { get; } = onsetSample;
        public double LevelDbfs { get; } = levelDbfs;
        public double RatioDb { get; } = ratioDb;
        public double OnsetPeak { get; } = onsetPeak;
        public double PeakDbfs { get; set; } = levelDbfs;
        public double CrestPower { get; set; } = crestPower;
        public int CrestSampleCount { get; set; } = crestSampleCount;
        public bool SawDecay { get; set; }
    }

    private sealed class Sequence(long firstSample, long lastSample, int count, Candidate secondCandidate)
    {
        public long FirstSample { get; } = firstSample;
        public long LastSample { get; set; } = lastSample;
        public int Count { get; set; } = count;
        public bool Poisoned { get; set; }
        public Candidate SecondCandidate { get; set; } = secondCandidate;
        public double SecondCrestFactor { get; set; }
    }
}
