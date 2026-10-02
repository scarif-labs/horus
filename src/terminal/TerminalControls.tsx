import React from 'react';
import {Image, StyleSheet, Text, View} from 'react-native';
import type {TerminalToolchainTarget} from '../native/NativeTerminalRuntime';
import {InteractivePressable as Pressable} from '../ui/InteractivePressable';
import {UI_FONT_FAMILY} from '../ui/typography';
import {uiColors} from './palette';
import {TERMINAL_BACKGROUND, TERMINAL_FOREGROUND} from './terminalBuffer';

const RETURN_ICON = require('./key-icons/return.png');

/** The final byte of an arrow key's escape sequence. */
export type TerminalArrow = 'A' | 'B' | 'C' | 'D';

type TerminalToolbarButtonProps = Readonly<{
  active?: boolean;
  accessibilityLabel: string;
  disabled: boolean;
  label: string;
  onPress: () => void;
  testID: string;
}>;

function TerminalToolbarButton({active = false, accessibilityLabel, disabled, label, onPress, testID}: TerminalToolbarButtonProps): React.JSX.Element {
  return (
    <Pressable
      accessibilityLabel={accessibilityLabel}
      accessibilityRole="button"
      accessibilityState={{selected: active}}
      disabled={disabled}
      onPress={onPress}
      style={[styles.keyButton, active && styles.modifierButtonActive]}
      testID={testID}>
      <Text style={[styles.keyButtonText, active && styles.modifierButtonTextActive]}>{label}</Text>
    </Pressable>
  );
}

type TerminalReturnButtonProps = Readonly<{
  disabled: boolean;
  onPress: () => void;
}>;

function TerminalReturnUpperButton({disabled, onPress}: TerminalReturnButtonProps): React.JSX.Element {
  return (
    <Pressable
      accessibilityLabel="Return"
      accessibilityRole="button"
      disabled={disabled}
      onPress={onPress}
      style={styles.returnKeyUpper}
      testID="terminal-key-return-upper" />
  );
}

function TerminalReturnLowerButton({disabled, onPress}: TerminalReturnButtonProps): React.JSX.Element {
  return (
    <Pressable
      accessibilityLabel="Return"
      accessibilityRole="button"
      disabled={disabled}
      onPress={onPress}
      style={styles.returnKeyLower}
      testID="terminal-key-return">
      <View pointerEvents="none" style={styles.returnKeyNotch} testID="terminal-key-return-notch" />
      {/* An image, not ↵: the UI font draws that glyph below the baseline. */}
      <Image source={RETURN_ICON} style={styles.returnKeyIcon} />
      <Text style={styles.returnKeyText}>RETURN</Text>
    </Pressable>
  );
}

export type TerminalControlsProps = Readonly<{
  altActive: boolean;
  ctrlActive: boolean;
  keyboardVisible: boolean;
  onArrow: (direction: TerminalArrow) => void;
  onEscape: () => void;
  onKeyboardToggle: () => void;
  onPaste: () => void;
  onReturn: () => void;
  onTerminalKey: (value: string) => void;
  onToggleAlt: () => void;
  onToggleCtrl: () => void;
  onToggleTranscriptPager: () => void;
  running: boolean;
  toolchain: TerminalToolchainTarget;
  transcriptPagerOpen: boolean;
}>;

export const TerminalControls = React.memo(function TerminalControlsView({altActive, ctrlActive, keyboardVisible, onArrow, onEscape, onKeyboardToggle, onPaste, onReturn, onTerminalKey, onToggleAlt, onToggleCtrl, onToggleTranscriptPager, running, toolchain, transcriptPagerOpen}: TerminalControlsProps): React.JSX.Element {
  return (
    <View style={styles.controls}>
      <View style={styles.controlRows}>
        <View style={styles.controlRow}>
          <View style={styles.controlRowMain}>
            <TerminalToolbarButton accessibilityLabel="Escape" disabled={!running} label="ESC" onPress={onEscape} testID="terminal-key-esc" />
            <TerminalToolbarButton accessibilityLabel="Slash" disabled={!running} label="/" onPress={() => onTerminalKey('/')} testID="terminal-key-slash" />
            <TerminalToolbarButton accessibilityLabel="Dash" disabled={!running} label="―" onPress={() => onTerminalKey('-')} testID="terminal-key-dash" />
            <TerminalToolbarButton accessibilityLabel={keyboardVisible ? 'Hide keyboard' : 'Show keyboard'} disabled={!running} label={keyboardVisible ? 'HIDE' : 'SHOW'} onPress={onKeyboardToggle} testID="terminal-keyboard-toggle" />
            <TerminalToolbarButton accessibilityLabel="Arrow up" disabled={!running} label="↑" onPress={() => onArrow('A')} testID="terminal-key-arrow-up" />
            <TerminalToolbarButton accessibilityLabel="Paste" disabled={!running} label="PASTE" onPress={onPaste} testID="terminal-key-paste" />
            {toolchain === 'codex' && running ? (
              <TerminalToolbarButton
                accessibilityLabel={transcriptPagerOpen ? 'Close transcript history' : 'Open transcript history'}
                disabled={!running}
                label={transcriptPagerOpen ? 'CHAT' : 'HIST'}
                onPress={onToggleTranscriptPager}
                testID="terminal-key-transcript-history"
              />
            ) : null}
          </View>
          <TerminalReturnUpperButton disabled={!running} onPress={onReturn} />
        </View>
        <View style={styles.controlRow}>
          <View style={styles.controlRowMain}>
            <TerminalToolbarButton accessibilityLabel="Tab" disabled={!running} label="TAB" onPress={() => onTerminalKey('\t')} testID="terminal-key-tab" />
            <TerminalToolbarButton accessibilityLabel="Control modifier" active={ctrlActive} disabled={!running} label="CTRL" onPress={onToggleCtrl} testID="terminal-key-ctrl" />
            <TerminalToolbarButton accessibilityLabel="Alt modifier" active={altActive} disabled={!running} label="ALT" onPress={onToggleAlt} testID="terminal-key-alt" />
            <TerminalToolbarButton accessibilityLabel="Arrow left" disabled={!running} label="←" onPress={() => onArrow('D')} testID="terminal-key-arrow-left" />
            <TerminalToolbarButton accessibilityLabel="Arrow down" disabled={!running} label="↓" onPress={() => onArrow('B')} testID="terminal-key-arrow-down" />
            <TerminalToolbarButton accessibilityLabel="Arrow right" disabled={!running} label="→" onPress={() => onArrow('C')} testID="terminal-key-arrow-right" />
          </View>
          <TerminalReturnLowerButton disabled={!running} onPress={onReturn} />
        </View>
      </View>
    </View>
  );
});

const styles = StyleSheet.create({
  controls: {backgroundColor: TERMINAL_BACKGROUND, paddingVertical: 4},
  controlRows: {width: '100%'},
  controlRow: {flexDirection: 'row', height: 42, width: '100%'},
  controlRowMain: {borderTopColor: uiColors.border, borderTopWidth: 1, flex: 1, flexDirection: 'row', minWidth: 0},
  keyButton: {alignItems: 'center', backgroundColor: TERMINAL_BACKGROUND, borderRightColor: uiColors.borderSoft, borderRightWidth: 1, flex: 1, justifyContent: 'center', minWidth: 0, paddingHorizontal: 0},
  keyButtonText: {color: TERMINAL_FOREGROUND, fontFamily: UI_FONT_FAMILY, fontSize: 10, fontWeight: '800'},
  modifierButtonActive: {backgroundColor: uiColors.accent},
  modifierButtonTextActive: {color: TERMINAL_BACKGROUND},
  returnKeyUpper: {backgroundColor: TERMINAL_BACKGROUND, borderColor: uiColors.border, borderLeftWidth: 1, borderRightWidth: 1, borderTopWidth: 1, height: 42, width: 72},
  returnKeyLower: {alignItems: 'center', backgroundColor: TERMINAL_BACKGROUND, borderBottomColor: uiColors.border, borderBottomWidth: 1, borderColor: uiColors.border, borderLeftWidth: 1, borderRightWidth: 1, borderTopColor: TERMINAL_BACKGROUND, borderTopWidth: 1, flexDirection: 'row', height: 42, justifyContent: 'center', position: 'relative', width: 96},
  returnKeyNotch: {backgroundColor: uiColors.border, height: 1, left: 0, position: 'absolute', top: 0, width: 24},
  returnKeyIcon: {height: 18, tintColor: uiColors.accent, width: 18},
  returnKeyText: {color: TERMINAL_FOREGROUND, fontFamily: UI_FONT_FAMILY, fontSize: 10, fontWeight: '800', includeFontPadding: false, letterSpacing: 0.3, marginLeft: 6},
});
