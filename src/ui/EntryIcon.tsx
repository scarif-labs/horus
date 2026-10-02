import React from 'react';
import {Image, StyleSheet, type ImageStyle, type StyleProp} from 'react-native';
import {uiColors} from './brand';

/**
 * Folder and file icons, shared by the project chooser and the file
 * explorer. The PNGs are white on transparent (sources in assets/ui/*.svg),
 * so tintColor sets the colour.
 *
 * - folder: a folder on this device
 * - folder-outline: a GitHub repository that has not been cloned yet
 * - file: a regular file
 * - refresh: the reload action on a list
 * - bell, battery: the background permissions
 * - check: a completed item
 */
export type EntryIconKind = 'folder' | 'folder-outline' | 'file' | 'refresh' | 'bell' | 'battery' | 'check';

const SOURCES = {
  folder: require('../../assets/ui/folder.png'),
  'folder-outline': require('../../assets/ui/folder-outline.png'),
  file: require('../../assets/ui/file.png'),
  refresh: require('../../assets/ui/refresh.png'),
  bell: require('../../assets/ui/bell.png'),
  battery: require('../../assets/ui/battery.png'),
  check: require('../../assets/ui/check.png'),
} as const;

const DEFAULT_TINT: Record<EntryIconKind, string> = {
  folder: uiColors.accent,
  'folder-outline': uiColors.muted,
  file: uiColors.muted,
  refresh: uiColors.accent,
  bell: uiColors.ink,
  battery: uiColors.ink,
  check: uiColors.background,
};

type EntryIconProps = Readonly<{kind: EntryIconKind; size?: number; tint?: string; style?: StyleProp<ImageStyle>}>;

export function EntryIcon({kind, size = 24, tint, style}: EntryIconProps): React.JSX.Element {
  return (
    <Image
      accessibilityElementsHidden
      importantForAccessibility="no"
      resizeMode="contain"
      source={SOURCES[kind]}
      style={[styles.icon, {height: size, tintColor: tint ?? DEFAULT_TINT[kind], width: size}, style]}
      testID={`entry-icon-${kind}`}
    />
  );
}

const styles = StyleSheet.create({
  icon: {marginRight: 12},
});
