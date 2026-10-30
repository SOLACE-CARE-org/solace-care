import { colors, radius, size, space } from '@solace/tokens/native';
import * as DocumentPicker from 'expo-document-picker';
import { useTranslation } from 'react-i18next';
import { Pressable, StyleSheet, View } from 'react-native';

import type { PickedFile } from '@/api/specialist';

import { Text } from './Text';

const ANY = ['application/pdf', 'image/jpeg', 'image/png'];

/**
 * One document to attach, as on SpecDocuments: its name, whether it is in, and Add or Replace. PDF,
 * JPEG or PNG; Core checks the file again.
 */
export function FileRow({
  title,
  file,
  required,
  onPick,
}: {
  title: string;
  file: PickedFile | null;
  required: boolean;
  onPick: (file: PickedFile) => void;
}) {
  const { t } = useTranslation();
  const action = t(file ? 'specialistSignup.documents.replace' : 'specialistSignup.documents.add');

  const pick = async () => {
    const result = await DocumentPicker.getDocumentAsync({ type: ANY, copyToCacheDirectory: true });
    if (result.canceled || result.assets.length === 0) return;
    const a = result.assets[0];
    onPick({ uri: a.uri, name: a.name, mimeType: a.mimeType });
  };

  return (
    <View style={styles.row}>
      <View style={styles.text}>
        <Text variant="label">{title}</Text>
        <Text variant="meta" color={colors.inkMuted}>
          {file
            ? t('specialistSignup.documents.uploaded', { name: file.name })
            : t(
                required
                  ? 'specialistSignup.documents.required'
                  : 'specialistSignup.documents.optional',
              )}
        </Text>
      </View>
      <Pressable
        accessibilityRole="button"
        accessibilityLabel={`${action}: ${title}`}
        onPress={() => void pick()}
        style={styles.button}
      >
        <Text variant="label" color={colors.brand}>
          {action}
        </Text>
      </Pressable>
    </View>
  );
}

const styles = StyleSheet.create({
  row: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: space.space12,
    padding: space.space14,
    borderRadius: radius.radius14,
    borderWidth: 1,
    borderColor: colors.line,
    backgroundColor: colors.surfaceCard,
  },
  text: {
    flex: 1,
    gap: space.space2,
  },
  button: {
    minHeight: size.sizeTap,
    justifyContent: 'center',
    paddingHorizontal: space.space14,
    borderRadius: radius.radius10,
    borderWidth: 1,
    borderColor: colors.brand,
  },
});
