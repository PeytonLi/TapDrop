using System.Text.Json;
using TapDrop.Core;

return await Cli.RunAsync(args);

internal static class Cli
{
    public static async Task<int> RunAsync(string[] args)
    {
        if (args.Length == 0) return Usage();
        try
        {
            return args[0] switch
            {
                "pair-new" => await PairNewAsync(args[1..]),
                "join" => await JoinAsync(args[1..]),
                "listen" => await ListenAsync(args[1..]),
                "send-text" => await SendTextAsync(args[1..]),
                "send-url" => await SendUrlAsync(args[1..]),
                "send-file" => await SendFileAsync(args[1..]),
                _ => Usage(),
            };
        }
        catch (Exception error)
        {
            Console.Error.WriteLine(error.Message);
            return 1;
        }
    }

    private static async Task<int> PairNewAsync(string[] args)
    {
        var options = Parse(args);
        var relayUrl = options.GetValueOrDefault("relay");
        if (string.IsNullOrWhiteSpace(relayUrl)) relayUrl = RelayDefaults.Url;
        var pair = PairInfo.Generate(relayUrl, Require(options, "name"));
        await using var client = new RelayClient(pair);
        await client.RegisterAsync();
        await SavePairAsync(Require(options, "out"), pair);
        Console.WriteLine(pair.ToQrPayload());
        return 0;
    }

    private static async Task<int> JoinAsync(string[] args)
    {
        var options = Parse(args);
        var pair = PairInfo.FromQrPayload(Require(options, "qr"), Require(options, "name"));
        await using var client = new RelayClient(pair);
        await client.RegisterAsync();
        await SavePairAsync(Require(options, "out"), pair);
        Console.WriteLine($"Joined pair {pair.PairId} as {pair.DeviceName}.");
        return 0;
    }

    private static async Task<int> ListenAsync(string[] args)
    {
        var options = Parse(args);
        var pair = await LoadPairAsync(Require(options, "config"));
        var role = Require(options, "role");
        var saveDir = Path.GetFullPath(Require(options, "save-dir"));
        Directory.CreateDirectory(saveDir);
        using var cancellation = new CancellationTokenSource();
        Console.CancelKeyPress += (_, eventArgs) =>
        {
            eventArgs.Cancel = true;
            cancellation.Cancel();
        };

        await using var client = new RelayClient(pair);
        client.ConnectionChanged += (connected, peerOnline) =>
            Console.Error.WriteLine(connected
                ? $"Connected to relay (peer {(peerOnline ? "online" : "offline")})."
                : "Disconnected from relay.");
        await client.StartAsync(role, async received =>
        {
            string? destination = null;
            if (received.Item.Kind is "text" or "url")
            {
                var content = received.Item.Text ?? received.Item.Url ?? "";
                destination = Path.Combine(saveDir, $"{SafeName(received.MessageId)}.txt");
                await File.WriteAllTextAsync(destination, content, cancellation.Token);
            }
            else if (received.BlobPath is not null)
            {
                var filename = SafeName(received.Item.Blob?.Name ?? received.MessageId);
                destination = UniquePath(saveDir, filename);
                File.Copy(received.BlobPath, destination);
            }

            Console.WriteLine(ProtocolJson.Serialize(new
            {
                id = received.MessageId,
                from = received.From,
                item = received.Item,
                path = destination,
            }));
        }, cancellation.Token);
        try
        {
            await client.Completion;
        }
        catch (OperationCanceledException) { }
        return 0;
    }

    private static async Task<int> SendTextAsync(string[] args)
    {
        var options = Parse(args);
        var pair = await LoadPairAsync(Require(options, "config"));
        var role = Require(options, "role");
        await using var client = new RelayClient(pair);
        await client.RegisterAsync();
        await client.StartAsync(role, _ => Task.CompletedTask);
        Console.WriteLine(await client.SendTextAsync(Require(options, "text"), role));
        return 0;
    }

    private static async Task<int> SendUrlAsync(string[] args)
    {
        var options = Parse(args);
        var pair = await LoadPairAsync(Require(options, "config"));
        var role = Require(options, "role");
        await using var client = new RelayClient(pair);
        await client.RegisterAsync();
        await client.StartAsync(role, _ => Task.CompletedTask);
        Console.WriteLine(await client.SendUrlAsync(Require(options, "url"), role));
        return 0;
    }

    private static async Task<int> SendFileAsync(string[] args)
    {
        var options = Parse(args);
        var pair = await LoadPairAsync(Require(options, "config"));
        var role = Require(options, "role");
        await using var client = new RelayClient(pair);
        await client.RegisterAsync();
        await client.StartAsync(role, _ => Task.CompletedTask);
        Console.WriteLine(await client.SendFileAsync(
            Require(options, "path"),
            role,
            options.GetValueOrDefault("mime"),
            options.GetValueOrDefault("kind") ?? "file"));
        return 0;
    }

    private static async Task SavePairAsync(string path, PairInfo pair)
    {
        var fullPath = Path.GetFullPath(path);
        Directory.CreateDirectory(Path.GetDirectoryName(fullPath)!);
        await File.WriteAllTextAsync(fullPath, JsonSerializer.Serialize(pair, ProtocolJson.Options));
    }

    private static async Task<PairInfo> LoadPairAsync(string path)
    {
        var json = await File.ReadAllTextAsync(path);
        return JsonSerializer.Deserialize<PairInfo>(json, ProtocolJson.Options)
            ?? throw new InvalidDataException("Pair config is empty.");
    }

    private static Dictionary<string, string> Parse(string[] args)
    {
        var options = new Dictionary<string, string>(StringComparer.Ordinal);
        for (var index = 0; index < args.Length; index++)
        {
            if (!args[index].StartsWith("--", StringComparison.Ordinal) || index + 1 >= args.Length)
                throw new ArgumentException($"Expected --option value, got '{args[index]}'.");
            options[args[index][2..]] = args[++index];
        }
        return options;
    }

    private static string Require(Dictionary<string, string> options, string name) =>
        options.TryGetValue(name, out var value) && !string.IsNullOrWhiteSpace(value)
            ? value
            : throw new ArgumentException($"Missing --{name}.");

    private static string SafeName(string name)
    {
        var invalid = Path.GetInvalidFileNameChars().ToHashSet();
        return string.Concat(name.Select(character => invalid.Contains(character) ? '_' : character));
    }

    private static string UniquePath(string directory, string name)
    {
        var path = Path.Combine(directory, name);
        var baseName = Path.GetFileNameWithoutExtension(name);
        var extension = Path.GetExtension(name);
        for (var index = 1; File.Exists(path); index++)
            path = Path.Combine(directory, $"{baseName} ({index}){extension}");
        return path;
    }

    private static int Usage()
    {
        Console.Error.WriteLine(
            "TapDrop CLI: pair-new [--relay URL] --name N --out F | join --qr PAYLOAD --name N --out F | " +
            "listen --config F --role laptop|phone --save-dir D | send-text --config F --role R --text TEXT | " +
            "send-url --config F --role R --url URL | send-file --config F --role R --path FILE [--mime MIME] [--kind file|image]");
        return 2;
    }
}
