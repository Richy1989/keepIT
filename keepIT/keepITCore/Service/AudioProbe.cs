using System.Buffers.Binary;
using System.Text;

namespace keepITCore.Service;

/// <summary>An accepted audio container, or the reason the bytes were refused.</summary>
public enum AudioFormat
{
    /// <summary>Not audio this server accepts.</summary>
    None = 0,

    /// <summary>MPEG-4 audio (what the Android recorder produces).</summary>
    M4a,

    /// <summary>Ogg (Vorbis or Opus).</summary>
    Ogg,

    /// <summary>MPEG audio layer III.</summary>
    Mp3,

    /// <summary>Uncompressed RIFF/WAVE.</summary>
    Wav,
}

/// <summary>What a probe found: the container, and its duration when it could be read.</summary>
/// <param name="Format">The recognised container, or <see cref="AudioFormat.None"/>.</param>
/// <param name="Extension">The extension to store the file under, including the dot.</param>
/// <param name="DurationMs">Playing time when the container exposed it cheaply; null otherwise.</param>
public readonly record struct AudioInfo(AudioFormat Format, string Extension, int? DurationMs)
{
    /// <summary>True when the bytes are audio this server will store.</summary>
    public bool IsAudio => Format != AudioFormat.None;
}

/// <summary>
/// Identifies audio by its bytes, the way <see cref="NoteMediaProcessor"/> identifies images —
/// except that audio is stored <b>exactly as uploaded</b>. There is no audio encoder in the
/// container and adding ffmpeg to ship voice notes would be a large dependency for a self-hosted
/// image, so the re-encode that strips an image's GPS metadata has no equivalent here. That makes
/// identifying the container the only line of defence, which is why it is done by signature and
/// never by the file name the client sent.
/// <para>
/// For MPEG-4 this goes further than a signature: it walks the box tree to prove the file has a
/// sound track and <em>no</em> video track, so the endpoint cannot be used to park video in a notes
/// app. That same walk yields the duration from <c>mvhd</c>, which is why m4a — the format the app
/// records — is the one that shows a running time.
/// </para>
/// </summary>
public static class AudioProbe
{
    /// <summary>Enough bytes for every signature below; the MP4 walk reads more as it needs it.</summary>
    public const int HeaderBytes = 16;

    /// <summary>How deep the MP4 box walk will nest before giving up on a malformed file.</summary>
    private const int MaxBoxDepth = 6;

    /// <summary>
    /// A cheap forward-only look at the first bytes: could these be one of the accepted containers?
    /// Used to decide whether an upload is worth spooling somewhere seekable for <see cref="Identify"/>,
    /// which for MPEG-4 has to walk the box tree and cannot do that on a one-pass stream.
    /// </summary>
    /// <param name="header">At least <see cref="HeaderBytes"/> bytes from the start of the file.</param>
    /// <returns>True when the signature matches an audio container this server accepts.</returns>
    public static bool HasAudioSignature(ReadOnlySpan<byte> header)
    {
        if (header.Length < HeaderBytes) return false;
        return header[4..8].SequenceEqual("ftyp"u8)
            || header[..4].SequenceEqual("OggS"u8)
            || (header[..4].SequenceEqual("RIFF"u8) && header[8..12].SequenceEqual("WAVE"u8))
            || header[..3].SequenceEqual("ID3"u8)
            || (header[0] == 0xFF && (header[1] & 0xE6) >= 0xE2);
    }

    /// <summary>
    /// Identifies the audio in a seekable stream, leaving the position where it found it — callers
    /// re-open or rewind before storing.
    /// </summary>
    /// <param name="stream">The uploaded bytes, positioned at 0.</param>
    /// <returns>The container and duration, or <see cref="AudioFormat.None"/>.</returns>
    public static AudioInfo Identify(Stream stream)
    {
        Span<byte> header = stackalloc byte[HeaderBytes];
        if (!TryFill(stream, header)) return default;

        if (header[4..8].SequenceEqual("ftyp"u8))
            return Mp4(stream);

        if (header[..4].SequenceEqual("OggS"u8))
            return new AudioInfo(AudioFormat.Ogg, ".ogg", null);

        if (header[..4].SequenceEqual("RIFF"u8) && header[8..12].SequenceEqual("WAVE"u8))
            return new AudioInfo(AudioFormat.Wav, ".wav", null);

        // An ID3 tag, or a bare MPEG frame header (11 sync bits then a non-reserved layer).
        if (header[..3].SequenceEqual("ID3"u8) || (header[0] == 0xFF && (header[1] & 0xE6) >= 0xE2))
            return new AudioInfo(AudioFormat.Mp3, ".mp3", null);

        return default;
    }

    /// <summary>
    /// Walks an MPEG-4 file far enough to decide it is audio and to read its duration.
    /// </summary>
    /// <param name="stream">The uploaded bytes.</param>
    /// <returns>An m4a result, or none when the file has video or no sound track.</returns>
    private static AudioInfo Mp4(Stream stream)
    {
        if (!stream.CanSeek) return default;
        stream.Position = 0;

        var moov = FindBox(stream, 0, stream.Length, "moov"u8, depth: 0);
        if (moov is not var (moovStart, moovEnd)) return default;

        var handlers = new List<string>();
        CollectHandlers(stream, moovStart, moovEnd, handlers, depth: 0);

        // A notes app stores voice notes, not films. An MPEG-4 file with a video track is refused
        // even though it would otherwise decode, because accepting it turns an attachment endpoint
        // into video hosting.
        if (handlers.Contains("vide")) return default;
        if (!handlers.Contains("soun")) return default;

        return new AudioInfo(AudioFormat.M4a, ".m4a", MovieDuration(stream, moovStart, moovEnd));
    }

    /// <summary>Reads <c>mvhd</c>'s timescale and duration, in milliseconds.</summary>
    /// <param name="stream">The file.</param>
    /// <param name="start">First byte inside <c>moov</c>.</param>
    /// <param name="end">One past the last byte of <c>moov</c>.</param>
    /// <returns>The duration, or null when it is absent or nonsensical.</returns>
    private static int? MovieDuration(Stream stream, long start, long end)
    {
        if (FindBox(stream, start, end, "mvhd"u8, depth: 0) is not var (mvhd, mvhdEnd)) return null;
        if (mvhdEnd - mvhd < 4) return null;

        stream.Position = mvhd;
        Span<byte> versionAndFlags = stackalloc byte[4];
        if (!TryFill(stream, versionAndFlags)) return null;

        // Version 0 stores 32-bit times after two 32-bit timestamps; version 1 stores 64-bit times
        // after two 64-bit ones.
        ulong timescale;
        ulong duration;
        if (versionAndFlags[0] == 1)
        {
            Span<byte> body = stackalloc byte[28];
            if (!TryFill(stream, body)) return null;
            timescale = BinaryPrimitives.ReadUInt32BigEndian(body[16..20]);
            duration = BinaryPrimitives.ReadUInt64BigEndian(body[20..28]);
        }
        else
        {
            Span<byte> body = stackalloc byte[16];
            if (!TryFill(stream, body)) return null;
            timescale = BinaryPrimitives.ReadUInt32BigEndian(body[8..12]);
            duration = BinaryPrimitives.ReadUInt32BigEndian(body[12..16]);
        }

        if (timescale == 0 || duration == 0) return null;

        var ms = duration * 1000d / timescale;
        // 0xFFFFFFFF is the "unknown duration" convention, and anything past a day is not a voice
        // note; either way it is better shown as no duration than as nonsense.
        return ms is > 0 and < 86_400_000 ? (int)ms : null;
    }

    /// <summary>Collects every <c>hdlr</c> handler type under a box, which says what each track is.</summary>
    /// <param name="stream">The file.</param>
    /// <param name="start">First byte inside the container box.</param>
    /// <param name="end">One past its last byte.</param>
    /// <param name="found">Handler types seen so far.</param>
    /// <param name="depth">Nesting guard.</param>
    private static void CollectHandlers(Stream stream, long start, long end, List<string> found, int depth)
    {
        if (depth > MaxBoxDepth) return;

        // Allocated once for the whole walk: a stackalloc per iteration would grow the frame with
        // every box in the file.
        Span<byte> handler = stackalloc byte[4];

        foreach (var (type, bodyStart, bodyEnd) in Boxes(stream, start, end))
        {
            if (type == "hdlr")
            {
                // version/flags(4) + pre_defined(4) + handler_type(4)
                if (bodyEnd - bodyStart < 12) continue;
                stream.Position = bodyStart + 8;
                if (TryFill(stream, handler)) found.Add(Encoding.ASCII.GetString(handler));
            }
            else if (type is "trak" or "mdia" or "minf" or "stbl")
            {
                CollectHandlers(stream, bodyStart, bodyEnd, found, depth + 1);
            }
        }
    }

    /// <summary>Finds the first box of a type, searching nested containers.</summary>
    /// <param name="stream">The file.</param>
    /// <param name="start">Where to start.</param>
    /// <param name="end">Where to stop.</param>
    /// <param name="wanted">The four-character box type.</param>
    /// <param name="depth">Nesting guard.</param>
    /// <returns>The box's body range, or null.</returns>
    private static (long Start, long End)? FindBox(
        Stream stream, long start, long end, ReadOnlySpan<byte> wanted, int depth)
    {
        if (depth > MaxBoxDepth) return null;
        var name = Encoding.ASCII.GetString(wanted);

        foreach (var (type, bodyStart, bodyEnd) in Boxes(stream, start, end))
        {
            if (type == name) return (bodyStart, bodyEnd);
        }
        return null;
    }

    /// <summary>
    /// Enumerates the boxes in a range: four bytes of size, four of type, then the body. A size of
    /// 0 means "to the end of the file" and 1 means a 64-bit size follows; anything that would run
    /// past <paramref name="end"/> or fail to advance stops the walk rather than looping.
    /// </summary>
    /// <param name="stream">The file.</param>
    /// <param name="start">First byte to read.</param>
    /// <param name="end">One past the last byte to read.</param>
    /// <returns>Each box's type and body range.</returns>
    private static IEnumerable<(string Type, long BodyStart, long BodyEnd)> Boxes(
        Stream stream, long start, long end)
    {
        var position = start;
        while (position + 8 <= end)
        {
            stream.Position = position;
            var head = new byte[8];
            if (!TryFill(stream, head)) yield break;

            long size = BinaryPrimitives.ReadUInt32BigEndian(head.AsSpan(0, 4));
            var type = Encoding.ASCII.GetString(head, 4, 4);
            var bodyStart = position + 8;

            if (size == 1)
            {
                var large = new byte[8];
                if (!TryFill(stream, large)) yield break;
                size = (long)BinaryPrimitives.ReadUInt64BigEndian(large);
                bodyStart += 8;
            }
            else if (size == 0)
            {
                size = end - position;
            }

            var boxEnd = position + size;
            if (size < 8 || boxEnd > end || bodyStart > boxEnd) yield break;

            yield return (type, bodyStart, boxEnd);
            position = boxEnd;
        }
    }

    /// <summary>Reads exactly as many bytes as the buffer holds.</summary>
    /// <param name="stream">The source.</param>
    /// <param name="buffer">The buffer to fill.</param>
    /// <returns>True when it was filled; false at end of stream.</returns>
    private static bool TryFill(Stream stream, Span<byte> buffer)
    {
        var read = 0;
        while (read < buffer.Length)
        {
            var got = stream.Read(buffer[read..]);
            if (got <= 0) return false;
            read += got;
        }
        return true;
    }
}
