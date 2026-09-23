using System.Buffers.Binary;
using System.Text;

namespace keepITCore.Tests.TestHost;

/// <summary>
/// Builds MPEG-4 files by hand, the way <see cref="TestImages"/> builds PNGs: just enough real
/// structure for the code under test, with no encoder and no fixture files in the repo.
///
/// <para>Only the boxes <c>AudioProbe</c> actually reads are written — <c>ftyp</c> to mark the
/// container, <c>moov/mvhd</c> for the duration, and a <c>trak</c> whose <c>hdlr</c> says what the
/// track is. That last one is the point of the video variant: the probe has to be able to tell a
/// voice note from a film without decoding either.</para>
/// </summary>
public static class TestAudio
{
    /// <summary>An MPEG-4 file with one sound track, of the given length.</summary>
    /// <param name="durationMs">The duration to record in <c>mvhd</c>.</param>
    /// <returns>The file's bytes.</returns>
    public static byte[] M4a(int durationMs) => Mp4("soun", durationMs);

    /// <summary>An MPEG-4 file with a video track, which must be refused.</summary>
    /// <param name="durationMs">The duration to record in <c>mvhd</c>.</param>
    /// <returns>The file's bytes.</returns>
    public static byte[] Mp4Video(int durationMs) => Mp4("vide", durationMs);

    /// <summary>An MPEG-4 file with no media track at all, which must also be refused.</summary>
    /// <returns>The file's bytes.</returns>
    public static byte[] Mp4WithoutTracks() =>
        Concat(FtypBox(), Box("moov", MvhdBox(1000)));

    /// <summary>The four bytes an Ogg stream starts with, enough for the signature check.</summary>
    /// <returns>A minimal Ogg-looking file.</returns>
    public static byte[] Ogg() => Concat("OggS"u8.ToArray(), new byte[32]);

    /// <summary>An MP3 with an ID3 tag, enough for the signature check.</summary>
    /// <returns>A minimal MP3-looking file.</returns>
    public static byte[] Mp3() => Concat("ID3"u8.ToArray(), new byte[32]);

    /// <summary>A RIFF/WAVE header, enough for the signature check.</summary>
    /// <returns>A minimal WAV-looking file.</returns>
    public static byte[] Wav() =>
        Concat("RIFF"u8.ToArray(), new byte[4], "WAVE"u8.ToArray(), new byte[32]);

    private static byte[] Mp4(string handler, int durationMs) =>
        Concat(
            FtypBox(),
            Box("moov", Concat(
                MvhdBox(durationMs),
                Box("trak", Box("mdia", HdlrBox(handler))))));

    /// <summary>The brand box every MPEG-4 file opens with.</summary>
    private static byte[] FtypBox() =>
        Box("ftyp", Concat("M4A "u8.ToArray(), new byte[4], "M4A mp42isom"u8.ToArray()));

    /// <summary>
    /// A version-0 movie header. The timescale is 1000, so the duration is already milliseconds.
    /// </summary>
    private static byte[] MvhdBox(int durationMs)
    {
        var body = new byte[100];
        // 0..4 version + flags, left zero (version 0).
        // 4..8 creation, 8..12 modification, both irrelevant here.
        BinaryPrimitives.WriteUInt32BigEndian(body.AsSpan(12, 4), 1000);
        BinaryPrimitives.WriteUInt32BigEndian(body.AsSpan(16, 4), (uint)durationMs);
        return Box("mvhd", body);
    }

    /// <summary>A handler box naming what the track carries ("soun", "vide", …).</summary>
    private static byte[] HdlrBox(string handler)
    {
        var body = new byte[32];
        // 0..4 version + flags, 4..8 pre_defined, then the four-character handler type.
        Encoding.ASCII.GetBytes(handler).CopyTo(body.AsSpan(8, 4));
        return Box("hdlr", body);
    }

    /// <summary>Wraps a payload in a box: 32-bit size, four-character type, then the body.</summary>
    private static byte[] Box(string type, byte[] body)
    {
        var box = new byte[8 + body.Length];
        BinaryPrimitives.WriteUInt32BigEndian(box.AsSpan(0, 4), (uint)box.Length);
        Encoding.ASCII.GetBytes(type).CopyTo(box.AsSpan(4, 4));
        body.CopyTo(box.AsSpan(8));
        return box;
    }

    private static byte[] Concat(params byte[][] parts)
    {
        var result = new byte[parts.Sum(p => p.Length)];
        var at = 0;
        foreach (var part in parts)
        {
            part.CopyTo(result.AsSpan(at));
            at += part.Length;
        }
        return result;
    }
}
