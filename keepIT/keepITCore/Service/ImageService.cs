using Microsoft.AspNetCore.Mvc;

namespace keepITCore.Service
{
    public class ImageService
    {
        public async Task<string> Upload(IFormFile file, string uploadDir, CancellationToken ct)
        {
            var ext = Path.GetExtension(file.FileName).ToLowerInvariant();

            // Don't trust the client filename — generate your own
            var fileName = $"{Guid.NewGuid():N}{ext}";
            var savePath = Path.Combine(uploadDir, fileName);

            await using var stream = File.Create(savePath);
            await file.CopyToAsync(stream, ct);

            return fileName;
        }
    }
}
