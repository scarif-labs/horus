import React from 'react';
import {StyleSheet, Text, View} from 'react-native';
import {BrandMark} from '../terminal/BrandMark';
import {uiColors} from '../terminal/palette';
import {InteractivePressable as Pressable} from './InteractivePressable';
import {HORUS_WORDMARK_FONT_FAMILY, UI_FONT_FAMILY} from './typography';

export {uiColors};

type BrandHeaderProps = Readonly<{
  title: string;
  eyebrow?: string;
  meta?: string | null;
  action?: React.ReactNode;
}>;

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

type BrandBackButtonProps = Readonly<{
  onPress: () => void;
  testID: string;
  accessibilityLabel?: string;
}>;

/** The BACK button in a screen header; every screen uses this one. */
export function BrandBackButton({onPress, testID, accessibilityLabel = 'Back to home'}: BrandBackButtonProps): React.JSX.Element {
  return (
    <Pressable accessibilityLabel={accessibilityLabel} accessibilityRole="button" onPress={onPress} style={styles.back} testID={testID}>
      <Text style={styles.backText}>BACK</Text>
    </Pressable>
  );
}

const styles = StyleSheet.create({
  back: {alignItems: 'center', borderColor: uiColors.border, borderRadius: 9, borderWidth: 1, height: 38, justifyContent: 'center', marginLeft: 8, minWidth: 68, paddingHorizontal: 10},
  backText: {color: uiColors.accent, fontFamily: UI_FONT_FAMILY, fontSize: 10, fontWeight: '800', letterSpacing: 0.6},
  header: {marginBottom: 12, paddingTop: 8},
  headerRow: {alignItems: 'center', flexDirection: 'row', height: 60},
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
