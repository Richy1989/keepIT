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

    /// <summary>An animated GIF whose frames are each a different solid colour.</summary>
    public static byte[] AnimatedGif(int width, int height, int frames)
    {
        using var image = new Image<Rgba32>(width, height, new Rgba32(200, 60, 40));
        for (var i = 1; i < frames; i++)
        {
            using var next = new Image<Rgba32>(width, height, new Rgba32((byte)(40 * i), 160, 90));
            image.Frames.AddFrame(next.Frames.RootFrame);
        }

        using var stream = new MemoryStream();
        image.SaveAsGif(stream);
        return stream.ToArray();
    }

    /// <summary>
    /// A 1 × 1 GIF with a valid first frame and a malformed second one (zero width and height),
    /// which fails to decode. The upload is accepted only if frames after the first are never
    /// decoded, which makes this file proof of that.
    /// </summary>
    public static byte[] GifWithBrokenSecondFrame() =>
    [
        .."GIF89a"u8,
        1, 0, 1, 0, 0x80, 0, 0,             // 1 × 1 canvas, 2-colour global palette
        0, 0, 0, 0xFF, 0xFF, 0xFF,          // palette: black, white
        0x2C, 0, 0, 0, 0, 1, 0, 1, 0, 0,    // frame 1: 1 × 1 at 0,0
        0x02, 0x02, 0x44, 0x01, 0x00,       //   its LZW data
        0x2C, 0, 0, 0, 0, 0, 0, 0, 0, 0,    // frame 2: 0 × 0
        0x02, 0x02, 0x44, 0x01, 0x00,
        0x3B,                               // trailer
    ];

    /// <summary>
    /// A well-formed PNG under 100 bytes whose header claims <paramref name="width"/> ×
    /// <paramref name="height"/> pixels over almost no image data. The size costs nothing to
    /// claim; only decoding it would allocate the pixels.
    /// </summary>
    public static byte[] PngClaiming(int width, int height)
    {
        var header = new byte[13];
        System.Buffers.Binary.BinaryPrimitives.WriteInt32BigEndian(header, width);
        System.Buffers.Binary.BinaryPrimitives.WriteInt32BigEndian(header.AsSpan(4), height);
        header[8] = 8; // bit depth; colour type 2 (RGB) next, then default compression, filter, interlace
        header[9] = 2;

        using var data = new MemoryStream();
        using (var zlib = new System.IO.Compression.ZLibStream(data, System.IO.Compression.CompressionLevel.Optimal))
            zlib.Write(new byte[16]);

        using var png = new MemoryStream();
        png.Write([0x89, .."PNG\r\n\u001a\n"u8]);
        WriteChunk(png, "IHDR", header);
        WriteChunk(png, "IDAT", data.ToArray());
        WriteChunk(png, "IEND", []);
        return png.ToArray();
    }

    /// <summary>Appends a PNG chunk: length, type, data, and the CRC the decoder checks.</summary>
    private static void WriteChunk(Stream png, string type, byte[] data)
    {
        var typed = new byte[4 + data.Length];
        System.Text.Encoding.ASCII.GetBytes(type).CopyTo(typed, 0);
        data.CopyTo(typed, 4);

        Span<byte> number = stackalloc byte[4];
        System.Buffers.Binary.BinaryPrimitives.WriteInt32BigEndian(number, data.Length);
        png.Write(number);
        png.Write(typed);
        System.Buffers.Binary.BinaryPrimitives.WriteUInt32BigEndian(number, Crc32(typed));
        png.Write(number);
    }

    /// <summary>The CRC-32 PNG chunks carry (the zlib/IEEE polynomial).</summary>
    private static uint Crc32(ReadOnlySpan<byte> bytes)
    {
        var crc = 0xFFFFFFFFu;
        foreach (var b in bytes)
        {
            crc ^= b;
            for (var bit = 0; bit < 8; bit++)
                crc = (crc & 1) != 0 ? 0xEDB88320u ^ (crc >> 1) : crc >> 1;
        }
        return ~crc;
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
