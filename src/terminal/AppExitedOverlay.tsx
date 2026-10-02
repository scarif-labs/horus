import React from 'react';
import {ScrollView, StyleSheet, Text, View} from 'react-native';
import type {TerminalToolchainTarget} from '../native/NativeTerminalRuntime';
import {InteractivePressable as Pressable} from '../ui/InteractivePressable';
import {UI_FONT_FAMILY} from '../ui/typography';
import {HarnessMark} from './harnessLogos';
import {uiColors} from './palette';
import {toolchainInstallLabel} from './toolchainLabels';

export const HORUS_ISSUES_URL = 'https://github.com/scarif-labs/horus/issues/new';

export type AppExit = Readonly<{
  exitCode?: number;
  signal?: string;
  /** The app's last screen, as plain text. */
  output?: string;
}>;

/** Exit code 0 without a signal: the app closed itself, e.g. /exit. */
export function appExitedCleanly(exit: AppExit): boolean {
  return exit.signal === undefined && (exit.exitCode === undefined || exit.exitCode === 0);
}

function exitDetail(exit: AppExit): string | undefined {
  if (exit.signal !== undefined) return `Stopped by ${exit.signal}`;
  if (exit.exitCode !== undefined && exit.exitCode !== 0) return `Exit code ${exit.exitCode}`;
  return undefined;
}

// GitHub rejects very long prefilled URLs; the end of the output is what
// usually explains a crash.
const MAX_ISSUE_OUTPUT_CHARS = 3000;

/** A new-issue link prefilled with what Horus knows about the exit. */
export function appExitIssueUrl(
  toolchain: TerminalToolchainTarget,
  exit: AppExit,
  environment: Readonly<{appVersion?: string; androidVersion: string}>,
): string {
  const label = toolchainInstallLabel(toolchain);
  const output = exit.output === undefined ? undefined : exit.output.slice(-MAX_ISSUE_OUTPUT_CHARS);
  const body = [
    `${label} quit while running in Horus.`,
    '',
    `- Horus: ${environment.appVersion ?? 'unknown'}`,
    `- Android: ${environment.androidVersion}`,
    `- ${exitDetail(exit) ?? 'Exit code 0'}`,
    '',
    'Last screen (check it for anything private before posting):',
    '',
    '```',
    output ?? '(empty)',
    '```',
    '',
    'What were you doing when it quit?',
    '',
  ].join('\n');
  const query = `title=${encodeURIComponent(`${label} quit unexpectedly`)}&body=${encodeURIComponent(body)}`;
  return `${HORUS_ISSUES_URL}?${query}`;
}

type AppExitedOverlayProps = Readonly<{
  exit: AppExit;
  toolchain: TerminalToolchainTarget;
  onRestart: () => void;
  onReport: () => void;
  onBack?: () => void;
}>;

/** Covers the terminal after an app quits, with its last screen and next steps. */
export function AppExitedOverlay({exit, toolchain, onRestart, onReport, onBack}: AppExitedOverlayProps): React.JSX.Element {
  const label = toolchainInstallLabel(toolchain);
  const clean = appExitedCleanly(exit);
  const detail = exitDetail(exit);
  return (
    <View style={styles.overlay} testID="terminal-app-exited">
      <View style={styles.content}>
        <HarnessMark size={48} toolchain={toolchain} />
        <Text style={styles.title}>{clean ? `${label} closed` : `${label} quit unexpectedly`}</Text>
        {detail === undefined ? null : <Text style={styles.detail} testID="terminal-app-exited-detail">{detail}</Text>}
        {exit.output === undefined ? null : (
          <View style={styles.outputBox}>
            <Text style={styles.outputLabel}>LAST SCREEN</Text>
            <ScrollView
              ref={scroll => { scroll?.scrollToEnd({animated: false}); }}
              style={styles.outputScroll}>
              <Text selectable style={styles.output} testID="terminal-app-exited-output">{exit.output}</Text>
            </ScrollView>
          </View>
        )}
        <Pressable accessibilityRole="button" onPress={onRestart} style={styles.primary} testID="terminal-app-restart">
          <Text style={styles.primaryText}>OPEN {label.toUpperCase()} AGAIN</Text>
        </Pressable>
        <View style={styles.secondaryRow}>
          {clean ? null : (
            <Pressable accessibilityLabel="Report an issue on GitHub" accessibilityRole="link" onPress={onReport} style={styles.secondary} testID="terminal-app-report">
              <Text style={styles.secondaryText}>REPORT ISSUE ↗</Text>
            </Pressable>
          )}
          {onBack === undefined ? null : (
            <Pressable accessibilityRole="button" onPress={onBack} style={styles.secondary} testID="terminal-app-back">
              <Text style={styles.secondaryText}>BACK</Text>
            </Pressable>
          )}
        </View>
      </View>
    </View>
  );
}

const styles = StyleSheet.create({
  overlay: {backgroundColor: uiColors.background, bottom: 0, justifyContent: 'center', left: 0, paddingHorizontal: 24, position: 'absolute', right: 0, top: 0, zIndex: 9, elevation: 9},
  content: {alignItems: 'center', alignSelf: 'center', maxWidth: 420, width: '100%'},
  title: {color: uiColors.ink, fontFamily: UI_FONT_FAMILY, fontSize: 18, fontWeight: '800', marginTop: 16, textAlign: 'center'},
  detail: {color: uiColors.danger, fontFamily: UI_FONT_FAMILY, fontSize: 11, marginTop: 6},
  outputBox: {alignSelf: 'stretch', backgroundColor: uiColors.panel, borderColor: uiColors.border, borderRadius: 10, borderWidth: 1, marginTop: 20, paddingHorizontal: 12, paddingVertical: 10},
  outputLabel: {color: uiColors.muted, fontFamily: UI_FONT_FAMILY, fontSize: 9, letterSpacing: 0.9, marginBottom: 6},
  outputScroll: {maxHeight: 220},
  output: {color: uiColors.ink, fontFamily: UI_FONT_FAMILY, fontSize: 10, lineHeight: 15},
  primary: {alignItems: 'center', alignSelf: 'stretch', backgroundColor: uiColors.accent, borderRadius: 10, justifyContent: 'center', marginTop: 20, minHeight: 46},
  primaryText: {color: uiColors.background, fontFamily: UI_FONT_FAMILY, fontSize: 11, fontWeight: '800', letterSpacing: 0.6},
  secondaryRow: {alignSelf: 'stretch', flexDirection: 'row', gap: 10, marginTop: 10},
  secondary: {alignItems: 'center', borderColor: uiColors.border, borderRadius: 10, borderWidth: 1, flex: 1, justifyContent: 'center', minHeight: 42},
  secondaryText: {color: uiColors.ink, fontFamily: UI_FONT_FAMILY, fontSize: 10, fontWeight: '800', letterSpacing: 0.6},
});
