using System.Drawing.Drawing2D;
using System.Reflection;
using System.Runtime.InteropServices;

namespace TapDrop.Tray;

internal sealed class TrayIcons : IDisposable
{
    public Icon Unpaired { get; }
    public Icon Connecting { get; }
    public Icon Connected { get; }
    public Icon PeerOnline { get; }

    public TrayIcons()
    {
        var size = SystemInformation.SmallIconSize;
        using var stream = Assembly.GetExecutingAssembly().GetManifestResourceStream("TapDrop.ico")
            ?? throw new InvalidOperationException("The TapDrop icon resource is missing.");
        using var baseIcon = new Icon(stream, size);

        Unpaired = CreateStatusIcon(baseIcon, size, Color.FromArgb(0xEF, 0x44, 0x44));
        Connecting = CreateStatusIcon(baseIcon, size, Color.FromArgb(0xF5, 0x9E, 0x0B));
        Connected = CreateStatusIcon(baseIcon, size, null);
        PeerOnline = CreateStatusIcon(baseIcon, size, Color.FromArgb(0x22, 0xC5, 0x5E));
    }

    public void Dispose()
    {
        Unpaired.Dispose();
        Connecting.Dispose();
        Connected.Dispose();
        PeerOnline.Dispose();
    }

    private static Icon CreateStatusIcon(Icon baseIcon, Size size, Color? statusColor)
    {
        using var bitmap = new Bitmap(size.Width, size.Height);
        using (var graphics = Graphics.FromImage(bitmap))
        {
            graphics.Clear(Color.Transparent);
            graphics.SmoothingMode = SmoothingMode.AntiAlias;
            graphics.InterpolationMode = InterpolationMode.HighQualityBicubic;
            graphics.PixelOffsetMode = PixelOffsetMode.HighQuality;
            graphics.DrawIcon(baseIcon, new Rectangle(Point.Empty, size));

            if (statusColor is { } color)
            {
                var diameter = Math.Max(2, (int)Math.Round(Math.Min(size.Width, size.Height) * 0.45));
                var bounds = new RectangleF(
                    size.Width - diameter - 0.5f,
                    size.Height - diameter - 0.5f,
                    diameter - 1f,
                    diameter - 1f);
                using var brush = new SolidBrush(color);
                using var outline = new Pen(Color.White, 1);
                graphics.FillEllipse(brush, bounds);
                graphics.DrawEllipse(outline, bounds);
            }
        }

        var hicon = bitmap.GetHicon();
        try
        {
            return (Icon)Icon.FromHandle(hicon).Clone();
        }
        finally
        {
            DestroyIcon(hicon);
        }
    }

    [DllImport("user32.dll", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool DestroyIcon(IntPtr hIcon);
}
