import React from 'react';
import {Image, StyleSheet, Text, View} from 'react-native';
import {HORUS_WORDMARK_FONT_FAMILY, UI_FONT_FAMILY} from './typography';

export const uiColors = {
  background: '#090C0D',
  panel: '#0D1112',
  panelRaised: '#111617',
  border: '#30383B',
  borderSoft: '#232A2D',
  ink: '#F2F4F5',
  muted: '#AAB7C6',
  subdued: '#707C84',
  accent: '#80EB12',
  /** Green-tinted fill behind accent-bordered selected or success states. */
  accentSurface: '#172018',
  /** Near-black for inset text wells and for text on accent fills. */
  inset: '#090D0B',
  danger: '#FF8795',
  warning: '#FFC65C',
} as const;

type BrandHeaderProps = Readonly<{
  title: string;
  eyebrow?: string;
  meta?: string | null;
  action?: React.ReactNode;
}>;

export function BrandMark(): React.JSX.Element {
  return (
    <View accessible accessibilityLabel="Horus" style={styles.mark}>
      <Image source={require('../../assets/brand/horus.png')} resizeMode="contain" style={styles.markImage} />
    </View>
  );
}

export function BrandHeader({title, eyebrow = 'HORUS', meta = 'v0.1', action}: BrandHeaderProps): React.JSX.Element {
  const hasEyebrow = eyebrow.length > 0;
  return (
    <View style={styles.header}>
      <View style={styles.headerRow} testID="brand-header-row">
        <BrandMark />
        <View style={styles.divider} />
        <View style={[styles.heading, !hasEyebrow && styles.singleHeading]}>
          {hasEyebrow ? <Text numberOfLines={1} style={styles.eyebrow}>{eyebrow}</Text> : null}
          <Text numberOfLines={1} style={[styles.title, !hasEyebrow && styles.singleTitle, title === 'HORUS' && styles.horusWordmark]}>{title}</Text>
        </View>
        <View style={styles.endActions}>
          {meta === null ? null : <Text numberOfLines={1} style={styles.meta}>{meta}</Text>}
          {action}
        </View>
      </View>
      <View style={styles.rule} testID="brand-header-rule" />
    </View>
  );
}

const styles = StyleSheet.create({
  header: {marginBottom: 12, paddingTop: 8},
  headerRow: {alignItems: 'center', flexDirection: 'row', height: 60},
  mark: {height: 42, justifyContent: 'center', marginRight: 9, width: 42},
  markImage: {height: 42, width: 42},
  divider: {backgroundColor: uiColors.border, height: 44, marginRight: 10, width: 1},
  heading: {flex: 1, minWidth: 0},
  singleHeading: {justifyContent: 'center'},
  endActions: {alignItems: 'center', flexDirection: 'row'},
  eyebrow: {color: uiColors.muted, fontFamily: UI_FONT_FAMILY, fontSize: 10, letterSpacing: 0.7},
  title: {color: uiColors.ink, fontFamily: UI_FONT_FAMILY, fontSize: 22, fontWeight: '800', letterSpacing: 0.2, marginTop: 2},
  horusWordmark: {fontFamily: HORUS_WORDMARK_FONT_FAMILY, fontSize: 22, fontWeight: '400', letterSpacing: 2.5, includeFontPadding: false},
  singleTitle: {marginTop: 0},
  meta: {color: uiColors.muted, fontFamily: UI_FONT_FAMILY, fontSize: 10, marginLeft: 8},
  rule: {backgroundColor: uiColors.border, height: 1, marginTop: 8},
});
