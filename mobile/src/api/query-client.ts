import { QueryClient } from '@tanstack/react-query';

/**
 * Server state for the whole app. Weak networks are the norm (System Design, Low bandwidth), so
 * one retry, then the screen's error state with Try again.
 */
export const queryClient = new QueryClient({
  defaultOptions: {
    queries: { retry: 1, staleTime: 30_000, refetchOnWindowFocus: false },
    mutations: { retry: 0 },
  },
});
