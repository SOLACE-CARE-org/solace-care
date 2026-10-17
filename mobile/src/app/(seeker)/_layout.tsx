import { Tabs } from 'expo-router';
import { Calendar, ChartLine, House, User } from 'lucide-react-native';
import { useTranslation } from 'react-i18next';

import { flags } from '@/config/flags';
import { tab, tabBarOptions } from '@/ui/tabBar';

/** The seeker shell: Home, Track (Phase 2, behind a flag), Sessions, You. */
export default function SeekerTabs() {
  const { t } = useTranslation();
  return (
    <Tabs screenOptions={tabBarOptions(flags.trackTab ? 4 : 3)}>
      <Tabs.Screen name="home" options={tab(t('nav.seeker.home'), House)} />
      <Tabs.Screen
        name="track"
        options={{ ...tab(t('nav.seeker.track'), ChartLine), href: flags.trackTab ? undefined : null }}
      />
      <Tabs.Screen name="sessions" options={tab(t('nav.seeker.sessions'), Calendar)} />
      <Tabs.Screen name="you" options={tab(t('nav.seeker.you'), User)} />
    </Tabs>
  );
}
