namespace TapDrop.Core.Tests;

internal static class KnockSignals
{
    public static float[] Background(int sampleRate, double seconds, int seed)
    {
        var random = new Random(seed);
        var samples = new float[(int)Math.Round(sampleRate * seconds)];
        for (var index = 0; index < samples.Length; index++)
            samples[index] = (float)(NextGaussian(random) * 0.001);
        return samples;
    }

    public static void AddKnock(float[] samples, int sampleRate, double startSeconds,
        double amplitude = 0.5, double frequency = 2000, double durationMs = 10)
    {
        var start = (int)Math.Round(startSeconds * sampleRate);
        var duration = durationMs / 1000;
        var tau = duration / 4;
        var count = Math.Min(samples.Length - start, (int)Math.Ceiling(duration * sampleRate));
        for (var offset = 0; offset < count; offset++)
        {
            var time = offset / (double)sampleRate;
            samples[start + offset] += (float)(amplitude * Math.Sin(2 * Math.PI * frequency * time) * Math.Exp(-time / tau));
        }
    }

    public static void AddSpeech(float[] samples, int sampleRate, double startSeconds,
        double durationSeconds, double targetRms, int seed)
    {
        var random = new Random(seed);
        var count = (int)Math.Round(durationSeconds * sampleRate);
        var speech = new double[count];
        var envelope = new double[count];
        var lowpass = 0.0;
        var alpha = 1 - Math.Exp(-2 * Math.PI * 1000 / sampleRate);
        var cursor = 0;
        while (cursor < count)
        {
            var syllable = random.Next(80, 301) * sampleRate / 1000;
            var attack = 20 * sampleRate / 1000;
            var release = 20 * sampleRate / 1000;
            var active = Math.Min(syllable, count - cursor);
            for (var index = 0; index < active; index++)
            {
                var input = NextGaussian(random);
                lowpass += alpha * (input - lowpass);
                var fadeIn = Math.Min(1, index / (double)Math.Max(1, attack));
                var fadeOut = Math.Min(1, (active - index) / (double)Math.Max(1, release));
                envelope[cursor + index] = Math.Min(fadeIn, fadeOut);
                speech[cursor + index] = lowpass * envelope[cursor + index];
            }
            cursor += active + random.Next(50, 201) * sampleRate / 1000;
        }

        var rms = Math.Sqrt(speech.Sum(value => value * value) / Math.Max(1, speech.Length));
        var scale = targetRms / Math.Max(rms, 1e-12);
        var start = (int)Math.Round(startSeconds * sampleRate);
        for (var index = 0; index < speech.Length && start + index < samples.Length; index++)
            samples[start + index] += (float)(speech[index] * scale);
    }

    public static void AddTyping(float[] samples, int sampleRate, double startSeconds,
        double durationSeconds, double peakAmplitude, int seed)
    {
        var random = new Random(seed);
        var start = (int)Math.Round(startSeconds * sampleRate);
        var end = Math.Min(samples.Length, start + (int)Math.Round(durationSeconds * sampleRate));
        var cursor = start;
        while (cursor < end)
        {
            var burstSamples = Math.Min(end - cursor, 3 * sampleRate / 1000);
            var burst = new double[burstSamples];
            var peak = 0.0;
            for (var index = 0; index < burstSamples; index++)
            {
                var time = index / (double)sampleRate;
                burst[index] = NextGaussian(random) * Math.Exp(-time / 0.0015);
                peak = Math.Max(peak, Math.Abs(burst[index]));
            }
            var scale = peakAmplitude / Math.Max(peak, 1e-12);
            for (var index = 0; index < burstSamples; index++)
                samples[cursor + index] += (float)(burst[index] * scale);
            cursor += burstSamples + random.Next(100, 251) * sampleRate / 1000;
        }
    }

    public static void AddMusic(float[] samples, int sampleRate, double startSeconds,
        double durationSeconds, double peakAmplitude, bool abrupt = false)
    {
        var start = (int)Math.Round(startSeconds * sampleRate);
        var end = Math.Min(samples.Length, start + (int)Math.Round(durationSeconds * sampleRate));
        var tones = new[] { 220.0, 277.0, 330.0 };
        var max = 0.0;
        for (var index = start; index < end; index++)
        {
            var time = (index - start) / (double)sampleRate;
            var note = (int)(time / 0.25);
            var local = time % 0.25;
            var envelope = abrupt ? 1 : Math.Min(1, local / 0.02);
            var value = 0.0;
            for (var tone = 0; tone < tones.Length; tone++)
            {
                var frequency = tones[tone] * (1 + note % 4 * 0.04);
                value += Math.Sin(2 * Math.PI * frequency * time)
                    + 0.5 * Math.Sin(4 * Math.PI * frequency * time)
                    + 0.25 * Math.Sin(6 * Math.PI * frequency * time);
            }
            value *= envelope;
            samples[index] += (float)value;
            max = Math.Max(max, Math.Abs(value));
        }
        if (max <= 0) return;
        var scale = peakAmplitude / max;
        for (var index = start; index < end; index++)
        {
            var existing = samples[index];
            var time = (index - start) / (double)sampleRate;
            var note = (int)(time / 0.25);
            var local = time % 0.25;
            var envelope = abrupt ? 1 : Math.Min(1, local / 0.02);
            var value = 0.0;
            foreach (var tone in tones)
            {
                var frequency = tone * (1 + note % 4 * 0.04);
                value += Math.Sin(2 * Math.PI * frequency * time)
                    + 0.5 * Math.Sin(4 * Math.PI * frequency * time)
                    + 0.25 * Math.Sin(6 * Math.PI * frequency * time);
            }
            var scaledValue = value * envelope * scale;
            samples[index] = (float)(existing - value * envelope + scaledValue);
        }
    }

    private static double NextGaussian(Random random)
    {
        var first = 1 - random.NextDouble();
        var second = 1 - random.NextDouble();
        return Math.Sqrt(-2 * Math.Log(first)) * Math.Cos(2 * Math.PI * second);
    }
}
