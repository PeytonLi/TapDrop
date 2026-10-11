namespace TapDrop.Tray;

internal static class Program
{
    [STAThread]
    private static void Main(string[] args)
    {
        using var mutex = new Mutex(true, @"Local\TapDrop.Tray", out var isNewInstance);
        if (!isNewInstance)
        {
            try
            {
                using var client = new System.IO.Pipes.NamedPipeClientStream(".", TrayApplicationContext.PipeName, System.IO.Pipes.PipeDirection.Out);
                client.Connect(1500);
                using var writer = new StreamWriter(client);
                writer.WriteLine(System.Text.Json.JsonSerializer.Serialize(args));
            }
            catch (Exception error)
            {
                MessageBox.Show($"TapDrop is already running: {error.Message}", "TapDrop");
            }
            return;
        }

        ApplicationConfiguration.Initialize();
        Application.Run(new TrayApplicationContext(args));
    }
}
