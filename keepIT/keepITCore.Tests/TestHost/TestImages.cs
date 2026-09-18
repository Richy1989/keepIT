using SixLabors.ImageSharp;
using SixLabors.ImageSharp.PixelFormats;

namespace keepITCore.Tests.TestHost;

/// <summary>Image files to upload, made on the fly so the suite carries no binary fixtures.</summary>
public static class TestImages
{
    /// <summary>A solid-colour PNG of the given size.</summary>
    public static byte[] Png(int width, int height)
    {
        using var image = new Image<Rgb24>(width, height, new Rgb24(40, 120, 200));
        using var stream = new MemoryStream();
        image.SaveAsPng(stream);
        return stream.ToArray();
    }

    /// <summary>A solid-colour GIF of the given size.</summary>
    public static byte[] Gif(int width, int height)
    {
        using var image = new Image<Rgba32>(width, height, new Rgba32(200, 60, 40));
        using var stream = new MemoryStream();
        image.SaveAsGif(stream);
        return stream.ToArray();
    }

    /// <summary>
    /// The start of an iPhone HEIC photo: enough for the server's brand sniffing, which is all a
    /// refusal needs.
    /// </summary>
    public static byte[] HeicHeader()
    {
        var bytes = new byte[64];
        "\0\0\0ftypheic"u8.ToArray().CopyTo(bytes, 0);
        return bytes;
    }

    /// <summary>The pixel size of an encoded image.</summary>
    public static (int Width, int Height) SizeOf(byte[] encoded)
    {
        var info = Image.Identify(encoded);
        return (info.Width, info.Height);
    }
}
