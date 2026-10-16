import React from 'react';
import {ScrollView, StyleSheet, Text, View} from 'react-native';
import type {TerminalToolchainTarget} from '../native/NativeTerminalRuntime';
import {InteractivePressable as Pressable} from '../ui/InteractivePressable';
import {UI_FONT_FAMILY} from '../ui/typography';
import {HORUS_ISSUES_URL} from './AppExitedOverlay';
import {HarnessMark} from './harnessLogos';
import {installFailureCopy, type InstallFailure} from './installFailure';
import {uiColors} from './palette';
import {toolchainInstallLabel} from './toolchainLabels';

// GitHub rejects very long prefilled URLs; the end of the log is what counts.
const MAX_ISSUE_LOG_CHARS = 3000;

/** A new-issue link prefilled with what Horus knows about the failed install. */
export function installFailureIssueUrl(
  toolchain: TerminalToolchainTarget,
  failure: InstallFailure,
  environment: Readonly<{appVersion?: string; androidVersion: string}>,
): string {
  const label = toolchainInstallLabel(toolchain);
  const body = [
    `${label} failed to install in Horus.`,
    '',
    `- Horus: ${environment.appVersion ?? 'unknown'}`,
    `- Android: ${environment.androidVersion}`,
    `- Reason: ${failure.kind}${failure.stage === undefined ? '' : ` (${failure.stage})`}`,
    '',
    'Install log (check it for anything private before posting):',
    '',
    '```',
    failure.log?.slice(-MAX_ISSUE_LOG_CHARS) ?? '(empty)',
    '```',
    '',
  ].join('\n');
  const query = `title=${encodeURIComponent(`${label} install failed`)}&body=${encodeURIComponent(body)}`;
  return `${HORUS_ISSUES_URL}?${query}`;
}

type InstallFailedOverlayProps = Readonly<{
  failure: InstallFailure;
  toolchain: TerminalToolchainTarget;
  onRetry: () => void;
  onReport: () => void;
  onBack?: () => void;
}>;

/** Covers the terminal when a first-run install fails: why, the log, and what to do next. */
export function InstallFailedOverlay({failure, toolchain, onRetry, onReport, onBack}: InstallFailedOverlayProps): React.JSX.Element {
  const copy = installFailureCopy(failure, toolchain);
  // Nothing to report when the phone was simply offline.
  const reportable = failure.kind !== 'offline';
  return (
    <View style={styles.overlay} testID="terminal-install-failed">
      <ScrollView contentContainerStyle={styles.scroll}>
        <View style={styles.content}>
          <HarnessMark size={48} toolchain={toolchain} />
          <Text style={styles.title}>{copy.title}</Text>
          <Text style={styles.reason} testID="terminal-install-failed-reason">{copy.reason}</Text>
          <Text style={styles.advice} testID="terminal-install-failed-advice">{copy.advice}</Text>
          {failure.log === undefined ? null : (
            <View style={styles.logBox}>
              <Text style={styles.logLabel}>INSTALL LOG</Text>
              <ScrollView
                nestedScrollEnabled
                ref={scroll => { scroll?.scrollToEnd({animated: false}); }}
                style={styles.logScroll}>
                <Text selectable style={styles.log} testID="terminal-install-failed-log">{failure.log}</Text>
              </ScrollView>
            </View>
          )}
          <Pressable accessibilityRole="button" onPress={onRetry} style={styles.primary} testID="terminal-install-retry">
            <Text style={styles.primaryText}>TRY AGAIN</Text>
          </Pressable>
          <View style={styles.secondaryRow}>
            {reportable ? (
              <Pressable accessibilityLabel="Report an issue on GitHub" accessibilityRole="link" onPress={onReport} style={styles.secondary} testID="terminal-install-report">
                <Text style={styles.secondaryText}>REPORT ISSUE ↗</Text>
              </Pressable>
            ) : null}
            {onBack === undefined ? null : (
              <Pressable accessibilityRole="button" onPress={onBack} style={styles.secondary} testID="terminal-install-back">
                <Text style={styles.secondaryText}>BACK</Text>
              </Pressable>
            )}
          </View>
        </View>
      </ScrollView>
    </View>
  );
}

const styles = StyleSheet.create({
  overlay: {backgroundColor: uiColors.background, bottom: 0, left: 0, position: 'absolute', right: 0, top: 0, zIndex: 9, elevation: 9},
  scroll: {flexGrow: 1, justifyContent: 'center', paddingHorizontal: 24, paddingVertical: 72},
  content: {alignItems: 'center', alignSelf: 'center', maxWidth: 420, width: '100%'},
  title: {color: uiColors.ink, fontFamily: UI_FONT_FAMILY, fontSize: 18, fontWeight: '800', marginTop: 16, textAlign: 'center'},
  reason: {color: uiColors.danger, fontFamily: UI_FONT_FAMILY, fontSize: 12, fontWeight: '700', marginTop: 8, textAlign: 'center'},
  advice: {color: uiColors.muted, fontFamily: UI_FONT_FAMILY, fontSize: 11, lineHeight: 17, marginTop: 10, textAlign: 'center'},
  logBox: {alignSelf: 'stretch', backgroundColor: uiColors.panel, borderColor: uiColors.border, borderRadius: 10, borderWidth: 1, marginTop: 20, paddingHorizontal: 12, paddingVertical: 10},
  logLabel: {color: uiColors.muted, fontFamily: UI_FONT_FAMILY, fontSize: 9, letterSpacing: 0.9, marginBottom: 6},
  logScroll: {maxHeight: 200},
  log: {color: uiColors.ink, fontFamily: UI_FONT_FAMILY, fontSize: 10, lineHeight: 15},
  primary: {alignItems: 'center', alignSelf: 'stretch', backgroundColor: uiColors.accent, borderRadius: 10, justifyContent: 'center', marginTop: 20, minHeight: 46},
  primaryText: {color: uiColors.background, fontFamily: UI_FONT_FAMILY, fontSize: 11, fontWeight: '800', letterSpacing: 0.6},
  secondaryRow: {alignSelf: 'stretch', flexDirection: 'row', gap: 10, marginTop: 10},
  secondary: {alignItems: 'center', borderColor: uiColors.border, borderRadius: 10, borderWidth: 1, flex: 1, justifyContent: 'center', minHeight: 42},
  secondaryText: {color: uiColors.ink, fontFamily: UI_FONT_FAMILY, fontSize: 10, fontWeight: '800', letterSpacing: 0.6},
});
