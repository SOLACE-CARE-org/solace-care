import { useEffect, useState } from 'react';

/**
 * The current time in milliseconds, updated every {@code everyMs}, so screens that depend on it
 * (Join opening 10 minutes before the start, "starts in 8 minutes") change on their own.
 */
export function useNow(everyMs = 30_000): number {
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => {
    const timer = setInterval(() => setNow(Date.now()), everyMs);
    return () => clearInterval(timer);
  }, [everyMs]);
  return now;
}
