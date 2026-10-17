import { colors, fontFamily, type as typeStyles } from '@solace/tokens/native';
import type { Tabs } from 'expo-router';
import type { ComponentProps, ComponentType } from 'react';
import { type ColorValue, Text } from 'react-native';

type ScreenOptions = NonNullable<ComponentProps<typeof Tabs.Screen>['options']>;
type Icon = ComponentType<{ size: number; strokeWidth: number; color: ColorValue }>;

/** 24px stroke icons at width 1.8 in the bottom nav (design-system README, Iconography). */
const ICON_SIZE = 24;
const ICON_STROKE = 1.8;

/**
 * BottomNav (design-system components/BottomNav): surface-card bar with a line top border,
 * nav-label text in ink-muted, the current tab in brand at weight 700. Side padding is 8px, or 4px
 * with five tabs.
 */
export function tabBarOptions(tabCount: number) {
  return {
    headerShown: false,
    tabBarActiveTintColor: colors.brand,
    tabBarInactiveTintColor: colors.inkMuted,
    tabBarStyle: {
      backgroundColor: colors.surfaceCard,
      borderTopColor: colors.line,
      borderTopWidth: 1,
      paddingHorizontal: tabCount > 4 ? 4 : 8,
    },
    sceneStyle: { backgroundColor: colors.surfacePage },
  };
}

/** One tab: its icon, and its label from the i18n catalogue (also its screen-reader label). */
export function tab(label: string, Glyph: Icon): ScreenOptions {
  return {
    title: label,
    tabBarAccessibilityLabel: label,
    tabBarIcon: ({ color }: { color: ColorValue }) => (
      <Glyph size={ICON_SIZE} strokeWidth={ICON_STROKE} color={color} />
    ),
    tabBarLabel: ({ focused, color }: { focused: boolean; color: ColorValue }) => (
      <Text
        style={{
          fontFamily: focused ? fontFamily.body[700] : typeStyles.navLabel.fontFamily,
          fontSize: typeStyles.navLabel.fontSize,
          lineHeight: typeStyles.navLabel.lineHeight,
          color,
        }}
      >
        {label}
      </Text>
    ),
  };
}
