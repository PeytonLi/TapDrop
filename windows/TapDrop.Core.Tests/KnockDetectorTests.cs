using TapDrop.Core;
using Xunit;

namespace TapDrop.Core.Tests;

public sealed class KnockDetectorTests
{
    [Theory]
    [InlineData(16000)]
    [InlineData(48000)]
    public void DoubleKnockGapsUseConfiguredWindow(int sampleRate)
    {
        AssertTriggers(DoubleKnock(sampleRate, 0.25), sampleRate, 1);
        AssertTriggers(DoubleKnock(sampleRate, 0.15), sampleRate, 1);
        AssertTriggers(DoubleKnock(sampleRate, 0.55), sampleRate, 1);
        AssertTriggers(DoubleKnock(sampleRate, 0.08), sampleRate, 0);
        AssertTriggers(DoubleKnock(sampleRate, 0.8), sampleRate, 0);
    }

    [Theory]
    [InlineData(16000)]
    [InlineData(48000)]
    public void SinglesTriplesAndFourKnocksDoNotTrigger(int sampleRate)
    {
        var single = KnockSignals.Background(sampleRate, 3, 101);
        KnockSignals.AddKnock(single, sampleRate, 1);
        AssertTriggers(single, sampleRate, 0);

        AssertTriggers(KnockSequence(sampleRate, [1, 1.25, 1.5]), sampleRate, 0);
        AssertTriggers(KnockSequence(sampleRate, [1, 1.25, 1.5, 1.75]), sampleRate, 0);
    }

    [Theory]
    [InlineData(16000)]
    [InlineData(48000)]
    public void TypingCannotTriggerAndSmallKeystrokesFailMinimumLevel(int sampleRate)
    {
        var small = KnockSignals.Background(sampleRate, 7, 201);
        KnockSignals.AddTyping(small, sampleRate, 1, 5, 0.08, 202);
        AssertTriggers(small, sampleRate, 0);

        var large = KnockSignals.Background(sampleRate, 7, 203);
        KnockSignals.AddTyping(large, sampleRate, 1, 5, 0.5, 204);
        AssertTriggers(large, sampleRate, 0);

        var twoSmall = KnockSignals.Background(sampleRate, 4, 205);
        KnockSignals.AddTyping(twoSmall, sampleRate, 1, 0.003, 0.08, 206);
        KnockSignals.AddTyping(twoSmall, sampleRate, 1.25, 0.003, 0.08, 207);
        AssertTriggers(twoSmall, sampleRate, 0);
    }

    [Theory]
    [InlineData(16000)]
    [InlineData(48000)]
    public void SpeechMusicAndAbruptContinuousSoundsAreRejected(int sampleRate)
    {
        var speech = KnockSignals.Background(sampleRate, 7, 301);
        KnockSignals.AddSpeech(speech, sampleRate, 1, 5, DbToAmplitude(-12), 302);
        AssertTriggers(speech, sampleRate, 0);

        var music = KnockSignals.Background(sampleRate, 7, 303);
        KnockSignals.AddMusic(music, sampleRate, 1, 5, 0.5);
        AssertTriggers(music, sampleRate, 0);

        var noise = KnockSignals.Background(sampleRate, 7, 304);
        AddLoudNoise(noise, sampleRate, 2, 4, DbToAmplitude(-6), 305);
        AssertTriggers(noise, sampleRate, 0);

        var chord = KnockSignals.Background(sampleRate, 7, 306);
        KnockSignals.AddMusic(chord, sampleRate, 1, 5, 0.7, abrupt: true);
        AssertTriggers(chord, sampleRate, 0);
    }

    [Theory]
    [InlineData(16000)]
    [InlineData(48000)]
    public void DoubleKnocksWorkOverSpeechMusicAndAfterTyping(int sampleRate)
    {
        var speech = KnockSignals.Background(sampleRate, 5, 401);
        KnockSignals.AddSpeech(speech, sampleRate, 1, 3, DbToAmplitude(-36), 402);
        KnockSignals.AddKnock(speech, sampleRate, 2);
        KnockSignals.AddKnock(speech, sampleRate, 2.25);
        AssertTriggers(speech, sampleRate, 1);

        var music = KnockSignals.Background(sampleRate, 5, 403);
        KnockSignals.AddMusic(music, sampleRate, 1, 3, 0.03);
        KnockSignals.AddKnock(music, sampleRate, 2);
        KnockSignals.AddKnock(music, sampleRate, 2.25);
        AssertTriggers(music, sampleRate, 1);

        var typingThenKnock = KnockSignals.Background(sampleRate, 9, 404);
        KnockSignals.AddTyping(typingThenKnock, sampleRate, 1, 5, 0.5, 405);
        KnockSignals.AddKnock(typingThenKnock, sampleRate, 7);
        KnockSignals.AddKnock(typingThenKnock, sampleRate, 7.25);
        AssertTriggers(typingThenKnock, sampleRate, 1);
    }

    [Theory]
    [InlineData(16000)]
    [InlineData(48000)]
    public void CooldownSuppressesNearbyPairButAllowsPairAfterCooldown(int sampleRate)
    {
        var suppressed = KnockSequenceWithSecondPair(sampleRate, 1);
        AssertTriggers(suppressed, sampleRate, 1);

        var allowed = KnockSequenceWithSecondPair(sampleRate, 2.5);
        AssertTriggers(allowed, sampleRate, 2);
    }

    [Theory]
    [InlineData(16000)]
    [InlineData(48000)]
    public void ChunkingDoesNotChangeEventStream(int sampleRate)
    {
        var samples = KnockSignals.Background(sampleRate, 4, 601);
        KnockSignals.AddKnock(samples, sampleRate, 1);
        KnockSignals.AddKnock(samples, sampleRate, 1.25);
        var whole = Detect(samples, sampleRate);
        var chunkedDetector = new KnockDetector(sampleRate);
        var chunked = new List<KnockEvent>();
        for (var offset = 0; offset < samples.Length; offset += 137)
            chunked.AddRange(chunkedDetector.Process(samples.AsSpan(offset, Math.Min(137, samples.Length - offset))));
        Assert.Equal(whole, chunked);
    }

    [Theory]
    [InlineData(16000)]
    [InlineData(48000)]
    public void SensitivityControlsQuietKnocksMonotonically(int sampleRate)
    {
        var samples = KnockSignals.Background(sampleRate, 4, 701);
        KnockSignals.AddKnock(samples, sampleRate, 1, 0.1);
        KnockSignals.AddKnock(samples, sampleRate, 1.25, 0.1);
        AssertTriggers(samples, sampleRate, 1, KnockConfig.FromSensitivity(10));
        AssertTriggers(samples, sampleRate, 0, KnockConfig.FromSensitivity(1));

        Assert.Equal(KnockConfig.Default, KnockConfig.FromSensitivity(5));
        for (var value = 1; value < 10; value++)
        {
            Assert.True(KnockConfig.FromSensitivity(value + 1).ThresholdDb
                <= KnockConfig.FromSensitivity(value).ThresholdDb);
            Assert.True(KnockConfig.FromSensitivity(value + 1).MinLevelDbfs
                <= KnockConfig.FromSensitivity(value).MinLevelDbfs);
        }
        Assert.Equal(KnockConfig.FromSensitivity(1), KnockConfig.FromSensitivity(-100));
        Assert.Equal(KnockConfig.FromSensitivity(10), KnockConfig.FromSensitivity(100));
    }

    [Theory]
    [InlineData(16000)]
    [InlineData(48000)]
    public void SingleKnockProducesCalibratableTransientEvent(int sampleRate)
    {
        var samples = KnockSignals.Background(sampleRate, 3, 801);
        KnockSignals.AddKnock(samples, sampleRate, 1);
        var events = Detect(samples, sampleRate);
        var transients = events.Where(item => item.Kind == KnockEventKind.Transient).ToArray();
        Assert.Single(transients);
        Assert.InRange(Math.Abs(transients[0].TimeSeconds - 1), 0, 0.01);
    }

    [Fact]
    public void RejectsUnsupportedSampleRates()
    {
        Assert.Throws<ArgumentOutOfRangeException>(() => new KnockDetector(7999));
        Assert.Throws<ArgumentOutOfRangeException>(() => new KnockDetector(192001));
    }

    private static float[] DoubleKnock(int sampleRate, double gap)
    {
        var samples = KnockSignals.Background(sampleRate, gap + 3, 901);
        KnockSignals.AddKnock(samples, sampleRate, 1, frequency: 1200, durationMs: 5);
        KnockSignals.AddKnock(samples, sampleRate, 1 + gap, frequency: 2800, durationMs: 15);
        return samples;
    }

    private static float[] KnockSequence(int sampleRate, double[] times)
    {
        var samples = KnockSignals.Background(sampleRate, times[^1] + 2, 902);
        foreach (var time in times) KnockSignals.AddKnock(samples, sampleRate, time);
        return samples;
    }

    private static float[] KnockSequenceWithSecondPair(int sampleRate, double delayAfterTrigger)
    {
        var samples = KnockSignals.Background(sampleRate, 7, 903);
        KnockSignals.AddKnock(samples, sampleRate, 1);
        KnockSignals.AddKnock(samples, sampleRate, 1.25);
        var secondStart = 1.25 + 0.4 + delayAfterTrigger;
        KnockSignals.AddKnock(samples, sampleRate, secondStart);
        KnockSignals.AddKnock(samples, sampleRate, secondStart + 0.25);
        return samples;
    }

    private static void AddLoudNoise(float[] samples, int sampleRate, double startSeconds,
        double durationSeconds, double rms, int seed)
    {
        var random = new Random(seed);
        var start = (int)Math.Round(startSeconds * sampleRate);
        var end = Math.Min(samples.Length, start + (int)Math.Round(durationSeconds * sampleRate));
        for (var index = start; index < end; index++)
            samples[index] += (float)(NextGaussian(random) * rms);
    }

    private static double NextGaussian(Random random)
    {
        var first = 1 - random.NextDouble();
        var second = 1 - random.NextDouble();
        return Math.Sqrt(-2 * Math.Log(first)) * Math.Cos(2 * Math.PI * second);
    }

    private static double DbToAmplitude(double db) => Math.Pow(10, db / 20);

    private static void AssertTriggers(float[] samples, int sampleRate, int expected,
        KnockConfig? config = null)
    {
        var events = Detect(samples, sampleRate, config);
        Assert.Equal(expected, events.Count(item => item.Kind == KnockEventKind.Trigger));
    }

    private static List<KnockEvent> Detect(float[] samples, int sampleRate, KnockConfig? config = null)
    {
        var detector = new KnockDetector(sampleRate, config);
        return detector.Process(samples).ToList();
    }
}
