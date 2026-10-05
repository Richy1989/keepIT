import { qrPath } from '../lib/qr';
import { cn } from '../lib/cn';

/**
 * A QR code from the rows of modules the server sends (see {@link qrPath}). Always black on white,
 * whatever the theme: scanners look for dark modules on a light ground, and many can't read the
 * inverse. The light border is part of the rows, so the white reaches the edge of the code.
 */
export function QrCode({ rows, label, className }: { rows: readonly string[]; label: string; className?: string }) {
  const size = rows.length;
  return (
    <svg
      role="img"
      aria-label={label}
      viewBox={`0 0 ${size} ${size}`}
      shapeRendering="crispEdges"
      className={cn('block rounded-lg', className)}
    >
      <rect width={size} height={size} fill="#ffffff" />
      <path d={qrPath(rows)} fill="#000000" />
    </svg>
  );
}
