import React from 'react';
import {Image, StyleSheet} from 'react-native';
import {uiColors} from './brand';

/**
 * Folder and file icons, shared by the project chooser and the file
 * explorer. The PNGs are white on transparent (sources in assets/ui/*.svg),
 * so tintColor sets the colour.
 *
 * - folder: a folder on this device
 * - folder-outline: a GitHub repository that has not been cloned yet
 * - file: a regular file
 */
export type EntryIconKind = 'folder' | 'folder-outline' | 'file';

const SOURCES = {
  folder: require('../../assets/ui/folder.png'),
  'folder-outline': require('../../assets/ui/folder-outline.png'),
  file: require('../../assets/ui/file.png'),
} as const;

const DEFAULT_TINT: Record<EntryIconKind, string> = {
  folder: uiColors.accent,
  'folder-outline': uiColors.muted,
  file: uiColors.muted,
};

type EntryIconProps = Readonly<{kind: EntryIconKind; size?: number; tint?: string}>;

export function EntryIcon({kind, size = 24, tint}: EntryIconProps): React.JSX.Element {
  return (
    <Image
      accessibilityElementsHidden
      importantForAccessibility="no"
      resizeMode="contain"
      source={SOURCES[kind]}
      style={[styles.icon, {height: size, tintColor: tint ?? DEFAULT_TINT[kind], width: size}]}
      testID={`entry-icon-${kind}`}
    />
  );
}

const styles = StyleSheet.create({
  icon: {marginRight: 12},
});
