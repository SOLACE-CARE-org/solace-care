import { Redirect } from 'expo-router';

import { useAuth } from '@/auth/auth';
import { useSession } from '@/state/session';

/**
 * Where the app opens: the splash and language choice on first launch, then the shell for the
 * person's role when they are signed in, or Welcome for guests.
 */
export default function Index() {
  const language = useSession((s) => s.language);
  const role = useSession((s) => s.role);
  const signedIn = useAuth((s) => s.status === 'signedIn');

  if (language === null) return <Redirect href="/splash" />;
  if (signedIn && role === 'seeker') return <Redirect href="/(seeker)/home" />;
  if (signedIn && role === 'specialist') return <Redirect href="/(specialist)/today" />;
  if (signedIn && role === 'applicant') return <Redirect href="/specialist-signup/resume" />;
  return <Redirect href="/welcome" />;
}
