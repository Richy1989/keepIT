/**
 * The SVG path of a QR code the server sent as rows of modules (`'1'` dark, `'0'` light; see
 * `TwoFactorSetupDto.qrCode`): one rectangle per run of dark modules in a row, in module units, so
 * the drawing is exact at any size. Nothing here knows how QR codes work, which is the point: the
 * server encodes, the clients only draw.
 */
export function qrPath(rows: readonly string[]): string {
  const parts: string[] = [];
  rows.forEach((row, y) => {
    let x = 0;
    while (x < row.length) {
      if (row[x] !== '1') {
        x++;
        continue;
      }
      const start = x;
      while (x < row.length && row[x] === '1') x++;
      parts.push(`M${start} ${y}h${x - start}v1h-${x - start}z`);
    }
  });
  return parts.join('');
}
