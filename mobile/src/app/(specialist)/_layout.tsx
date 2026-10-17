import { Tabs } from 'expo-router';
import { Calendar, Clock, FileText, User, Wallet } from 'lucide-react-native';
import { useTranslation } from 'react-i18next';

import { tab, tabBarOptions } from '@/ui/tabBar';

/** The specialist shell: Today, Calendar, Notes, Earnings, Profile. */
export default function SpecialistTabs() {
  const { t } = useTranslation();
  return (
    <Tabs screenOptions={tabBarOptions(5)}>
      <Tabs.Screen name="today" options={tab(t('nav.specialist.today'), Clock)} />
      <Tabs.Screen name="calendar" options={tab(t('nav.specialist.calendar'), Calendar)} />
      <Tabs.Screen name="notes" options={tab(t('nav.specialist.notes'), FileText)} />
      <Tabs.Screen name="earnings" options={tab(t('nav.specialist.earnings'), Wallet)} />
      <Tabs.Screen name="profile" options={tab(t('nav.specialist.profile'), User)} />
    </Tabs>
  );
}
