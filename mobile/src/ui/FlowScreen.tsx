import { colors, space } from '@solace/tokens/native';
import type { ReactNode } from 'react';
import { KeyboardAvoidingView, Platform, ScrollView, StyleSheet, View } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';

type Props = {
  /** The row at the top: a back button, or ProgressSteps. */
  top: ReactNode;
  children: ReactNode;
  /** The primary action; sits in a white footer with a top border, as on the sign-up screens. */
  footer?: ReactNode;
  /** Sign-in screens keep the action in the content instead of a footer. */
  plainFooter?: boolean;
};

/** The layout of the sign-in and sign-up screens (SignIn, VerifyCode, Seeker*.dc.html). */
export function FlowScreen({ top, children, footer, plainFooter = false }: Props) {
  return (
    <SafeAreaView style={styles.screen} edges={['top', 'bottom']}>
      <KeyboardAvoidingView
        style={styles.screen}
        behavior={Platform.OS === 'ios' ? 'padding' : undefined}
      >
        <ScrollView
          contentContainerStyle={styles.content}
          keyboardShouldPersistTaps="handled"
          bounces={false}
        >
          {top}
          {children}
        </ScrollView>
        {footer ? (
          <View style={plainFooter ? styles.plainFooter : styles.footer}>{footer}</View>
        ) : null}
      </KeyboardAvoidingView>
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  screen: {
    flex: 1,
    backgroundColor: colors.surfacePage,
  },
  content: {
    flexGrow: 1,
    paddingTop: space.space20,
    paddingHorizontal: space.space20,
    paddingBottom: space.space24,
    gap: space.space18,
  },
  footer: {
    paddingTop: space.space14,
    paddingHorizontal: space.space20,
    paddingBottom: space.space28,
    backgroundColor: colors.surfaceCard,
    borderTopWidth: 1,
    borderTopColor: colors.line,
  },
  plainFooter: {
    paddingHorizontal: space.space20,
    paddingBottom: space.space32,
  },
});
