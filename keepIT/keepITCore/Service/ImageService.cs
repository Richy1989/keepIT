namespace keepITCore.Service
{
    /// <summary>
    /// Stores uploaded images on disk under a caller-supplied directory. Defense in depth for
    /// uploads: the client's filename is never used (a fresh GUID name is generated) and the file's
    /// <em>content</em> must carry a known image signature — an extension check alone would let a
    /// renamed script or HTML file onto the disk.
    /// </summary>
    public class ImageService
    {
        /// <summary>
        /// Validates the file's magic bytes and, if it's a real image, writes it to
        /// <paramref name="uploadDir"/> under a generated name.
        /// </summary>
        /// <param name="file">The uploaded file.</param>
        /// <param name="uploadDir">The absolute directory to write into (must already exist).</param>
        /// <param name="ct">Cancellation token.</param>
        /// <returns>The generated filename, or null when the content is not a recognized image.</returns>
        public async Task<string?> Upload(IFormFile file, string uploadDir, CancellationToken ct)
        {
            var ext = Path.GetExtension(file.FileName).ToLowerInvariant();

            await using (var probe = file.OpenReadStream())
            {
                if (!await LooksLikeImageAsync(probe, ct))
                    return null;
            }

            // Don't trust the client filename — generate our own.
            var fileName = $"{Guid.NewGuid():N}{ext}";
            var savePath = Path.Combine(uploadDir, fileName);

            await using var stream = File.Create(savePath);
            await file.CopyToAsync(stream, ct);

            return fileName;
        }

        /// <summary>
        /// True when the stream starts with a JPEG, PNG, GIF, or WebP signature — the formats the
        /// profile-image endpoint accepts.
        /// </summary>
        private static async Task<bool> LooksLikeImageAsync(Stream stream, CancellationToken ct)
        {
            var header = new byte[12];
            var read = await stream.ReadAtLeastAsync(header, header.Length, throwOnEndOfStream: false, ct);

            // JPEG: FF D8 FF
            if (read >= 3 && header[0] == 0xFF && header[1] == 0xD8 && header[2] == 0xFF)
                return true;
            // PNG: 89 'P' 'N' 'G' 0D 0A 1A 0A
            if (read >= 8 && header[0] == 0x89 && header[1] == 'P' && header[2] == 'N' && header[3] == 'G'
                && header[4] == 0x0D && header[5] == 0x0A && header[6] == 0x1A && header[7] == 0x0A)
                return true;
            // GIF: "GIF87a" / "GIF89a"
            if (read >= 6 && header[0] == 'G' && header[1] == 'I' && header[2] == 'F'
                && header[3] == '8' && (header[4] == '7' || header[4] == '9') && header[5] == 'a')
                return true;
            // WebP: "RIFF" <size> "WEBP"
            if (read >= 12 && header[0] == 'R' && header[1] == 'I' && header[2] == 'F' && header[3] == 'F'
                && header[8] == 'W' && header[9] == 'E' && header[10] == 'B' && header[11] == 'P')
                return true;

            return false;
        }
    }
}
