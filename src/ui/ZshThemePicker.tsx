import React from 'react';
import {StyleSheet, Text, View} from 'react-native';
import type {ZshThemeId} from '../terminal/session/sessionSettings';
import {TERMINAL_BACKGROUND} from '../terminal/terminalBuffer';
import {uiColors} from './brand';
import {EntryIcon} from './EntryIcon';
import {InteractivePressable as Pressable} from './InteractivePressable';
import {UI_FONT_FAMILY} from './typography';

/** A run of prompt text in one style, as the terminal would draw it. */
type Span = readonly [text: string, color?: string, background?: string, bold?: boolean];

const BLUE = '#5c5cff';
const GREEN = '#00cd00';
const CYAN = '#00cdcd';
const RED = '#cd0000';
const MAGENTA = '#cd00cd';
const YELLOW = '#cdcd00';
const WHITE = '#ffffff';
const BLACK = '#000000';
const GRAY = '#6c6c6c';

const THEMES: readonly Readonly<{id: ZshThemeId; label: string; detail: string; preview: readonly (readonly Span[])[]}>[] = [
  {
    id: 'duellj',
    label: 'duellj',
    detail: 'Default. User, host, path and history number.',
    preview: [
      [['┌─[', BLUE, undefined, true], ['user', GREEN, undefined, true], ['@', BLUE, undefined, true], ['horus', CYAN], [']', BLUE, undefined, true], [' - '], ['[', BLUE, undefined, true], ['~/project', WHITE, undefined, true], [']', BLUE, undefined, true], [' - '], ['[', BLUE, undefined, true], ['42', YELLOW], [']', BLUE, undefined, true]],
      [['└─[', BLUE, undefined, true], ['$', MAGENTA, undefined, true], [']', BLUE, undefined, true]],
    ],
  },
  {
    id: 'robbyrussell',
    label: 'robbyrussell',
    detail: 'Oh My Zsh’s default. Folder and git branch.',
    preview: [[['➜', GREEN, undefined, true], [' '], ['project', CYAN], [' '], ['git:(', BLUE, undefined, true], ['main', RED, undefined, true], [')', BLUE, undefined, true]]],
  },
  {
    id: 'minimal',
    label: 'minimal',
    detail: 'Path, git branch and an arrow.',
    preview: [[['~/project', BLUE], [' '], ['main', GRAY], [' '], ['❯', MAGENTA]]],
  },
  {
    id: 'agnoster',
    label: 'agnoster',
    detail: 'Powerline segments, prompt on its own line.',
    preview: [
      [[' user@horus ', WHITE, BLACK], ['', BLACK, BLUE], [' ~/project ', BLACK, BLUE], ['', BLUE, GREEN], ['  main ', BLACK, GREEN], ['', GREEN]],
      [['❯', BLUE]],
    ],
  },
];

type ZshThemePickerProps = Readonly<{
  value: ZshThemeId | undefined;
  disabled: boolean;
  onSelect: (theme: ZshThemeId) => void;
}>;

type Theme = (typeof THEMES)[number];

function ThemePreview({theme}: Readonly<{theme: Theme}>): React.JSX.Element {
  return (
    <View style={styles.preview}>
      {theme.preview.map((line, lineIndex) => (
        <Text key={lineIndex} numberOfLines={1} style={styles.previewLine}>
          {line.map(([text, color, background, bold], spanIndex) => (
            <Text key={spanIndex} style={[{backgroundColor: background, color: color ?? uiColors.ink}, bold === true && styles.bold]}>{text}</Text>
          ))}
        </Text>
      ))}
    </View>
  );
}

/**
 * The current theme and its prompt; tapping it lists every theme, and
 * picking one folds the list away again.
 */
export function ZshThemePicker({value, disabled, onSelect}: ZshThemePickerProps): React.JSX.Element {
  const [expanded, setExpanded] = React.useState(false);
  const current = THEMES.find(theme => theme.id === value) ?? THEMES[0];

  if (!expanded) {
    return (
      <Pressable
        accessibilityHint="Shows every theme"
        accessibilityLabel={`Shell theme, ${current.label}`}
        accessibilityRole="button"
        accessibilityState={{disabled, expanded: false}}
        disabled={disabled}
        onPress={() => setExpanded(true)}
        style={[styles.row, disabled && styles.disabled]}
        testID="settings-zsh-theme-current">
        <View style={styles.rowLine}>
          <View style={styles.copy}>
            <Text style={styles.label}>{current.label}</Text>
            <Text style={styles.detail}>{current.detail}</Text>
          </View>
          <View style={styles.change}>
            <Text style={styles.changeText}>CHANGE</Text>
            {/* Drawn, not ▾: the UI font has no glyph and the fallback is tiny. */}
            <View style={styles.chevron} />
          </View>
        </View>
        <ThemePreview theme={current} />
      </Pressable>
    );
  }

  return (
    <View>
      {THEMES.map((theme, index) => {
        const selected = value === theme.id;
        return (
          <Pressable
            key={theme.id}
            accessibilityLabel={`${theme.label} theme. ${theme.detail}`}
            accessibilityRole="radio"
            accessibilityState={{checked: selected, disabled}}
            disabled={disabled}
            onPress={() => {
              setExpanded(false);
              onSelect(theme.id);
            }}
            style={[styles.row, index > 0 && styles.divider, disabled && styles.disabled]}
            testID={`settings-zsh-theme-${theme.id}`}>
            <View style={styles.rowLine}>
              <View style={styles.copy}>
                <Text style={styles.label}>{theme.label}</Text>
                <Text style={styles.detail}>{theme.detail}</Text>
              </View>
              {selected ? (
                <View style={styles.check} testID={`settings-zsh-theme-${theme.id}-selected`}>
                  <EntryIcon kind="check" size={13} style={styles.checkIcon} />
                </View>
              ) : <View style={styles.unchecked} />}
            </View>
            <ThemePreview theme={theme} />
          </Pressable>
        );
      })}
    </View>
  );
}

const styles = StyleSheet.create({
  row: {paddingHorizontal: 14, paddingVertical: 12},
  divider: {borderTopColor: uiColors.borderSoft, borderTopWidth: 1},
  disabled: {opacity: 0.55},
  rowLine: {alignItems: 'center', flexDirection: 'row'},
  copy: {flex: 1, minWidth: 0},
  label: {color: uiColors.ink, fontFamily: UI_FONT_FAMILY, fontSize: 12, fontWeight: '700'},
  detail: {color: uiColors.subdued, fontFamily: UI_FONT_FAMILY, fontSize: 9, lineHeight: 13, marginTop: 3},
  check: {alignItems: 'center', backgroundColor: uiColors.accent, borderRadius: 11, height: 22, justifyContent: 'center', marginLeft: 10, width: 22},
  checkIcon: {marginRight: 0},
  change: {alignItems: 'center', borderColor: uiColors.accent, borderRadius: 7, borderWidth: 1, flexDirection: 'row', gap: 8, marginLeft: 10, minHeight: 30, paddingHorizontal: 10},
  changeText: {color: uiColors.accent, fontFamily: UI_FONT_FAMILY, fontSize: 9, fontWeight: '800', letterSpacing: 0.5},
  chevron: {borderBottomWidth: 2, borderColor: uiColors.accent, borderRightWidth: 2, height: 7, marginTop: -4, transform: [{rotate: '45deg'}], width: 7},
  unchecked: {borderColor: uiColors.border, borderRadius: 11, borderWidth: 1, height: 22, marginLeft: 10, width: 22},
  preview: {backgroundColor: TERMINAL_BACKGROUND, borderColor: uiColors.borderSoft, borderRadius: 6, borderWidth: 1, marginTop: 10, paddingHorizontal: 8, paddingVertical: 6},
  previewLine: {fontFamily: UI_FONT_FAMILY, fontSize: 10, lineHeight: 15},
  bold: {fontWeight: '700'},
});
