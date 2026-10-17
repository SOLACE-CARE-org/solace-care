import { Redirect } from 'expo-router';

import { useSession } from '@/state/session';

/**
 * Where the app opens: the splash and language choice on first launch, then the shell for the
 * person's role, or Welcome for guests.
 */
export default function Index() {
  const language = useSession((s) => s.language);
  const role = useSession((s) => s.role);

  if (language === null) return <Redirect href="/splash" />;
  if (role === 'seeker') return <Redirect href="/(seeker)/home" />;
  if (role === 'specialist') return <Redirect href="/(specialist)/today" />;
  return <Redirect href="/welcome" />;
}
