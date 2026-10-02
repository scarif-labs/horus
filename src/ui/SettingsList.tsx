import React from 'react';
import {StyleSheet, Text, View} from 'react-native';
import {uiColors} from './brand';
import {EntryIcon, type EntryIconKind} from './EntryIcon';
import {InteractivePressable as Pressable} from './InteractivePressable';
import {UI_FONT_FAMILY} from './typography';

/**
 * Grouped settings list: a small header, one box of rows split by thin
 * dividers, and an optional one-line footer. Every Settings section uses it
 * so the screen reads as one list rather than a stack of look-alike cards.
 */
type SettingsSectionProps = Readonly<{
  title: string;
  footer?: React.ReactNode;
  footerTone?: 'muted' | 'warning' | 'danger';
  children: React.ReactNode;
  testID?: string;
}>;

export function SettingsSection({title, footer, footerTone = 'muted', children, testID}: SettingsSectionProps): React.JSX.Element {
  const rows = React.Children.toArray(children).filter(Boolean);
  return (
    <View style={styles.section} testID={testID}>
      <Text style={styles.sectionTitle}>{title}</Text>
      <View style={styles.group}>
        {rows.map((row, index) => (
          <View key={index} style={index > 0 ? styles.divider : undefined}>{row}</View>
        ))}
      </View>
      {footer === undefined ? null : typeof footer === 'string' ? (
        <Text style={[styles.footer, footerTone === 'warning' && styles.footerWarning, footerTone === 'danger' && styles.footerDanger]}>{footer}</Text>
      ) : footer}
    </View>
  );
}

type SettingsRowProps = Readonly<{
  icon?: EntryIconKind;
  label: string;
  detail?: string;
  right?: React.ReactNode;
  below?: React.ReactNode;
  testID?: string;
}>;

export function SettingsRow({icon, label, detail, right, below, testID}: SettingsRowProps): React.JSX.Element {
  return (
    <View style={styles.row} testID={testID}>
      <View style={styles.rowLine}>
        {icon === undefined ? null : <EntryIcon kind={icon} size={20} />}
        <View style={styles.rowCopy}>
          <Text style={styles.rowLabel}>{label}</Text>
          {detail === undefined ? null : <Text numberOfLines={2} style={styles.rowDetail}>{detail}</Text>}
        </View>
        {right}
      </View>
      {below}
    </View>
  );
}

type SettingsSwitchProps = Readonly<{
  value: boolean;
  disabled?: boolean;
  accessibilityLabel: string;
  onPress: () => void;
  testID?: string;
}>;

/** A pill switch; Pressable-based so it keeps onPress and the switch role. */
export function SettingsSwitch({value, disabled = false, accessibilityLabel, onPress, testID}: SettingsSwitchProps): React.JSX.Element {
  return (
    <Pressable
      accessibilityLabel={accessibilityLabel}
      accessibilityRole="switch"
      accessibilityState={{checked: value, disabled}}
      disabled={disabled}
      onPress={onPress}
      style={[styles.switchTrack, value && styles.switchTrackOn, disabled && styles.disabled]}
      testID={testID}>
      <View style={[styles.switchThumb, value && styles.switchThumbOn]} />
    </Pressable>
  );
}

type SettingsActionProps = Readonly<{label: string; tone?: 'accent' | 'danger'; disabled?: boolean; accessibilityLabel?: string; onPress: () => void; testID?: string}>;

/** A small text button for the right side of a row (ALLOW, REVOKE, RETRY). */
export function SettingsAction({label, tone = 'accent', disabled = false, accessibilityLabel, onPress, testID}: SettingsActionProps): React.JSX.Element {
  return (
    <Pressable
      accessibilityLabel={accessibilityLabel ?? label}
      accessibilityRole="button"
      disabled={disabled}
      onPress={onPress}
      style={[styles.action, tone === 'danger' && styles.actionDanger, disabled && styles.disabled]}
      testID={testID}>
      <Text style={[styles.actionText, tone === 'danger' && styles.actionTextDanger]}>{label}</Text>
    </Pressable>
  );
}

/** A green check for a setting that is already on. */
export function SettingsCheck({testID}: Readonly<{testID?: string}>): React.JSX.Element {
  return (
    <View accessibilityLabel="Allowed" style={styles.check} testID={testID}>
      <EntryIcon kind="check" size={13} style={styles.checkIcon} />
    </View>
  );
}

const styles = StyleSheet.create({
  section: {marginTop: 22},
  sectionTitle: {color: uiColors.muted, fontFamily: UI_FONT_FAMILY, fontSize: 9, letterSpacing: 0.9, marginBottom: 8, marginLeft: 4},
  group: {backgroundColor: uiColors.panel, borderColor: uiColors.border, borderRadius: 12, borderWidth: 1, overflow: 'hidden'},
  divider: {borderTopColor: uiColors.borderSoft, borderTopWidth: 1},
  footer: {color: uiColors.subdued, fontFamily: UI_FONT_FAMILY, fontSize: 9, lineHeight: 14, marginHorizontal: 4, marginTop: 8},
  footerWarning: {color: uiColors.warning},
  footerDanger: {color: uiColors.danger},
  row: {paddingHorizontal: 14, paddingVertical: 12},
  rowLine: {alignItems: 'center', flexDirection: 'row', minHeight: 32},
  rowCopy: {flex: 1, minWidth: 0},
  rowLabel: {color: uiColors.ink, fontFamily: UI_FONT_FAMILY, fontSize: 12, fontWeight: '700'},
  rowDetail: {color: uiColors.subdued, fontFamily: UI_FONT_FAMILY, fontSize: 9, lineHeight: 13, marginTop: 3},
  switchTrack: {backgroundColor: uiColors.background, borderColor: uiColors.border, borderRadius: 14, borderWidth: 1, height: 28, justifyContent: 'center', marginLeft: 10, paddingHorizontal: 3, width: 48},
  switchTrackOn: {backgroundColor: uiColors.accent, borderColor: uiColors.accent},
  switchThumb: {backgroundColor: uiColors.muted, borderRadius: 10, height: 20, width: 20},
  switchThumbOn: {alignSelf: 'flex-end', backgroundColor: uiColors.background},
  action: {alignItems: 'center', borderColor: uiColors.accent, borderRadius: 7, borderWidth: 1, justifyContent: 'center', marginLeft: 10, minHeight: 30, paddingHorizontal: 10},
  actionDanger: {borderColor: uiColors.danger},
  actionText: {color: uiColors.accent, fontFamily: UI_FONT_FAMILY, fontSize: 9, fontWeight: '800', letterSpacing: 0.5},
  actionTextDanger: {color: uiColors.danger},
  check: {alignItems: 'center', backgroundColor: uiColors.accent, borderRadius: 11, height: 22, justifyContent: 'center', marginLeft: 10, width: 22},
  checkIcon: {marginRight: 0},
  disabled: {opacity: 0.45},
});
