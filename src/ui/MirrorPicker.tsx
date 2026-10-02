import React from 'react';
import {StyleSheet, Text, TextInput, View} from 'react-native';
import {normalizeMirrorUrl, type MirrorOption} from '../terminal/downloadSources';
import {uiColors} from './brand';
import {InteractivePressable as Pressable} from './InteractivePressable';
import {UI_FONT_FAMILY} from './typography';

type MirrorPickerProps = Readonly<{
  options: readonly MirrorOption[];
  /** The chosen URL; undefined is the default server. */
  value: string | undefined;
  onSelect: (url: string | undefined) => void;
  disabled?: boolean;
  /** Example shown in the custom address field. */
  placeholder: string;
  testID: string;
}>;

/** Radio rows for preset mirrors, plus a custom https address. */
export function MirrorPicker({options, value, onSelect, disabled = false, placeholder, testID}: MirrorPickerProps): React.JSX.Element {
  const isPreset = options.some(option => option.url === value);
  const [customOpen, setCustomOpen] = React.useState(!isPreset);
  const [custom, setCustom] = React.useState(isPreset ? '' : value ?? '');
  const [error, setError] = React.useState(false);

  const useCustom = () => {
    const url = normalizeMirrorUrl(custom);
    setError(url === undefined);
    if (url !== undefined) onSelect(url);
  };

  return (
    <View testID={testID}>
      {options.map(option => (
        <Choice
          key={option.id}
          disabled={disabled}
          label={option.label}
          onPress={() => { setCustomOpen(false); onSelect(option.url); }}
          region={option.region}
          selected={!customOpen && value === option.url}
          testID={`${testID}-${option.id}`} />
      ))}
      <Choice disabled={disabled} label="Custom" onPress={() => setCustomOpen(true)} selected={customOpen} testID={`${testID}-custom`} />
      {customOpen ? (
        <View style={styles.custom}>
          <View style={styles.customRow}>
            <TextInput
              accessibilityLabel="Custom mirror address"
              autoCapitalize="none"
              autoCorrect={false}
              editable={!disabled}
              keyboardType="url"
              onChangeText={text => { setCustom(text); setError(false); }}
              onSubmitEditing={useCustom}
              placeholder={placeholder}
              placeholderTextColor={uiColors.subdued}
              style={styles.input}
              testID={`${testID}-custom-url`}
              value={custom} />
            <Pressable accessibilityRole="button" disabled={disabled} onPress={useCustom} style={styles.use} testID={`${testID}-custom-use`}>
              <Text style={styles.useText}>USE</Text>
            </Pressable>
          </View>
          {error ? <Text style={styles.error} testID={`${testID}-custom-error`}>Use an https:// address.</Text> : null}
        </View>
      ) : null}
    </View>
  );
}

type ChoiceProps = Readonly<{label: string; region?: string; selected: boolean; disabled: boolean; onPress: () => void; testID: string}>;

function Choice({label, region, selected, disabled, onPress, testID}: ChoiceProps): React.JSX.Element {
  return (
    <Pressable
      accessibilityLabel={region === undefined ? label : `${label}, ${region}`}
      accessibilityRole="radio"
      accessibilityState={{checked: selected, disabled}}
      disabled={disabled}
      onPress={onPress}
      style={[styles.choice, disabled && styles.disabled]}
      testID={testID}>
      <View style={[styles.radio, selected && styles.radioOn]}>{selected ? <View style={styles.radioDot} /> : null}</View>
      <Text style={styles.label}>{label}</Text>
      {region === undefined ? null : <Text style={styles.region}>{region}</Text>}
    </Pressable>
  );
}

const styles = StyleSheet.create({
  choice: {alignItems: 'center', flexDirection: 'row', gap: 12, minHeight: 40},
  disabled: {opacity: 0.5},
  radio: {alignItems: 'center', borderColor: uiColors.border, borderRadius: 9, borderWidth: 1.5, height: 18, justifyContent: 'center', width: 18},
  radioOn: {borderColor: uiColors.accent},
  radioDot: {backgroundColor: uiColors.accent, borderRadius: 4, height: 8, width: 8},
  label: {color: uiColors.ink, flex: 1, fontFamily: UI_FONT_FAMILY, fontSize: 12},
  region: {color: uiColors.subdued, fontFamily: UI_FONT_FAMILY, fontSize: 10},
  custom: {marginBottom: 6, marginLeft: 30},
  customRow: {flexDirection: 'row', gap: 8},
  input: {backgroundColor: uiColors.background, borderColor: uiColors.borderSoft, borderRadius: 8, borderWidth: 1, color: uiColors.ink, flex: 1, fontFamily: UI_FONT_FAMILY, fontSize: 11, minHeight: 40, paddingHorizontal: 10},
  use: {alignItems: 'center', borderColor: uiColors.accent, borderRadius: 8, borderWidth: 1, justifyContent: 'center', paddingHorizontal: 12},
  useText: {color: uiColors.accent, fontFamily: UI_FONT_FAMILY, fontSize: 10, fontWeight: '800'},
  error: {color: uiColors.danger, fontFamily: UI_FONT_FAMILY, fontSize: 10, marginTop: 6},
});
