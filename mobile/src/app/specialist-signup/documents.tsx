import { colors, radius, size, space } from '@solace/tokens/native';
import * as DocumentPicker from 'expo-document-picker';
import { router } from 'expo-router';
import { Check, FileText, IdCard, Lock, User } from 'lucide-react-native';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { ActivityIndicator, Pressable, StyleSheet, View } from 'react-native';

import { ApiProblem } from '@/api/core';
import {
  type DocumentKind,
  SPECIALIST_STEPS,
  resumeRoute,
  useApplication,
  useUploadDocument,
} from '@/api/specialist';
import { Button } from '@/ui/Button';
import { FlowScreen } from '@/ui/FlowScreen';
import { ProgressSteps } from '@/ui/ProgressSteps';
import { Text } from '@/ui/Text';

const KINDS: DocumentKind[] = ['LICENCE', 'IDENTITY', 'PHOTO', 'CV'];
const ANY = ['application/pdf', 'image/jpeg', 'image/png'];
const IMAGES = ['image/jpeg', 'image/png'];

/** Specialist application, step 4 of 7: documents (SpecDocuments.dc.html). */
export default function SpecialistDocuments() {
  const { t } = useTranslation();
  const application = useApplication();
  const upload = useUploadDocument();
  const [busyKind, setBusyKind] = useState<DocumentKind | null>(null);
  const [problem, setProblem] = useState<string | null>(null);

  const documents = application.data?.documents ?? [];
  const filesOf = (kind: DocumentKind) => documents.find((d) => d.kind === kind)?.files ?? [];
  const done = application.data?.steps.documents === true;

  const pick = async (kind: DocumentKind) => {
    setProblem(null);
    const result = await DocumentPicker.getDocumentAsync({
      type: kind === 'PHOTO' ? IMAGES : ANY,
      multiple: kind === 'IDENTITY',
      copyToCacheDirectory: true,
    });
    if (result.canceled || result.assets.length === 0) return;
    setBusyKind(kind);
    upload.mutate(
      {
        kind,
        files: result.assets
          .slice(0, kind === 'IDENTITY' ? 2 : 1)
          .map((a) => ({ uri: a.uri, name: a.name, mimeType: a.mimeType })),
      },
      {
        onError: (e) =>
          setProblem(e instanceof ApiProblem && e.title ? e.title : t('common.checkConnection')),
        onSettled: () => setBusyKind(null),
      },
    );
  };

  const meta = (kind: DocumentKind) => {
    const files = filesOf(kind);
    if (files.length > 0) {
      return kind === 'IDENTITY'
        ? t('specialistSignup.documents.identityUploaded')
        : t('specialistSignup.documents.uploaded', { name: files[0].fileName });
    }
    if (kind === 'PHOTO') return t('specialistSignup.documents.photoMeta');
    if (kind === 'CV') return t('specialistSignup.documents.optional');
    return t('specialistSignup.documents.required');
  };

  return (
    <FlowScreen
      top={<ProgressSteps step={4} total={SPECIALIST_STEPS} />}
      footer={
        <Button
          label={t('common.continue')}
          disabled={!done || busyKind !== null}
          accessibilityState={{ disabled: !done || busyKind !== null }}
          onPress={() => application.data && router.push(resumeRoute(application.data))}
        />
      }
    >
      <View style={styles.intro}>
        <Text variant="titleMd" accessibilityRole="header">
          {t('specialistSignup.documents.title')}
        </Text>
        <Text variant="body" color={colors.inkMuted}>
          {t('specialistSignup.documents.subtitle')}
        </Text>
      </View>

      <View style={styles.card}>
        {KINDS.map((kind, i) => {
          const uploaded = filesOf(kind).length > 0;
          const busy = busyKind === kind;
          const title = t(`specialistSignup.documents.${kind}`);
          const action = uploaded
            ? t('specialistSignup.documents.replace')
            : t('specialistSignup.documents.add');
          return (
            <View key={kind} style={[styles.row, i > 0 && styles.divider]}>
              <View style={[styles.icon, uploaded ? styles.iconDone : styles.iconTodo]} aria-hidden>
                <KindIcon kind={kind} uploaded={uploaded} />
              </View>
              <View style={styles.text}>
                <Text variant="label" style={styles.title}>
                  {title}
                </Text>
                <Text variant="meta" color={colors.inkMuted}>
                  {meta(kind)}
                </Text>
              </View>
              {busy ? (
                <ActivityIndicator
                  color={colors.brand}
                  accessibilityLabel={t('specialistSignup.documents.uploading')}
                />
              ) : (
                <Pressable
                  accessibilityRole="button"
                  accessibilityLabel={`${action}: ${title}`}
                  disabled={busyKind !== null}
                  onPress={() => pick(kind)}
                  style={uploaded ? styles.replace : styles.addButton}
                >
                  <Text variant="label" color={colors.brand}>
                    {action}
                  </Text>
                </Pressable>
              )}
            </View>
          );
        })}
      </View>

      <View style={styles.note}>
        <Lock size={16} strokeWidth={1.8} color={colors.inkMuted} aria-hidden />
        <Text variant="meta" color={colors.inkMuted} style={styles.noteText}>
          {t('specialistSignup.documents.note')}
        </Text>
      </View>

      {problem ? (
        <Text variant="meta" color={colors.danger} accessibilityLiveRegion="polite">
          {problem}
        </Text>
      ) : null}
    </FlowScreen>
  );
}

function KindIcon({ kind, uploaded }: { kind: DocumentKind; uploaded: boolean }) {
  const color = uploaded ? colors.brand : colors.inkMuted;
  if (uploaded) return <Check size={20} strokeWidth={1.8} color={color} />;
  if (kind === 'PHOTO') return <User size={20} strokeWidth={1.8} color={colors.warningInk} />;
  if (kind === 'IDENTITY') return <IdCard size={20} strokeWidth={1.8} color={color} />;
  return <FileText size={20} strokeWidth={1.8} color={color} />;
}

const styles = StyleSheet.create({
  intro: {
    gap: space.space6,
  },
  card: {
    borderRadius: radius.radius18,
    borderWidth: 1,
    borderColor: colors.line,
    backgroundColor: colors.surfaceCard,
  },
  row: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: space.space12,
    paddingVertical: space.space14,
    paddingHorizontal: space.space16,
  },
  divider: {
    borderTopWidth: 1,
    borderTopColor: colors.line,
  },
  icon: {
    width: 40,
    height: 40,
    borderRadius: radius.radius10,
    alignItems: 'center',
    justifyContent: 'center',
  },
  iconDone: {
    backgroundColor: colors.brandTint,
  },
  iconTodo: {
    backgroundColor: colors.surfaceSunken,
  },
  text: {
    flex: 1,
    gap: space.space2,
  },
  title: {
    fontSize: 15,
  },
  replace: {
    minHeight: size.sizeTap,
    justifyContent: 'center',
    paddingHorizontal: space.space8,
  },
  addButton: {
    minHeight: size.sizeTap,
    justifyContent: 'center',
    paddingHorizontal: space.space14,
    borderRadius: radius.radius10,
    borderWidth: 1,
    borderColor: colors.brand,
  },
  note: {
    flexDirection: 'row',
    gap: space.space8,
  },
  noteText: {
    flex: 1,
  },
});
