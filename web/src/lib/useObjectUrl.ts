import { useEffect, useState } from 'react';

/**
 * Turns a Blob into an object URL, revoking it when the blob changes or the component unmounts.
 *
 * Created and revoked in the *same* effect on purpose. With the URL minted in a `useMemo`,
 * StrictMode's mount→cleanup→mount cycle revoked a URL that nothing then recreated (a `useMemo`
 * doesn't re-run), leaving a dead blob in the <img> and leaking one URL per double-render.
 *
 * The setState-in-effect lint rule is waived here deliberately: an object URL is an external
 * resource whose lifetime must bracket the effect, which is exactly the "synchronize with an
 * external system" case. Deriving it in a useMemo instead is what caused that bug.
 */
/* eslint-disable react-hooks/set-state-in-effect */
export function useObjectUrl(blob: Blob | null | undefined): string | null {
  const [url, setUrl] = useState<string | null>(null);

  useEffect(() => {
    if (!blob) {
      setUrl(null);
      return;
    }
    const next = URL.createObjectURL(blob);
    setUrl(next);
    return () => {
      URL.revokeObjectURL(next);
      setUrl(null);
    };
  }, [blob]);

  return url;
}
/* eslint-enable react-hooks/set-state-in-effect */
