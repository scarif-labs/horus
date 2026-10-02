import React from 'react';
import {ActivityIndicator, StyleSheet, Text, View} from 'react-native';
import type {TerminalToolchainTarget} from '../native/NativeTerminalRuntime';
import {UI_FONT_FAMILY} from '../ui/typography';
import {HarnessMark} from './harnessLogos';
import {uiColors} from './palette';
import {toolchainInstallLabel} from './toolchainLabels';

/**
 * The provisioning script prints `HORUS_INSTALL_STAGE=<stage>` as it goes
 * (see ProotSessionLauncher's TOOLCHAIN_PROVISION_SCRIPT). This maps those
 * stages onto four user-facing steps.
 */
export function installStepForStage(stage: string, toolchain: TerminalToolchainTarget): number {
  if (stage === 'start') return 0;
  if (stage === 'apk') return 1;
  if (stage === 'github') return toolchain === 'github' ? 2 : 1;
  if (stage === 'ready') return 3;
  return 2;
}

const STAGE_PATTERN = /HORUS_INSTALL_STAGE=([a-z_]+)/g;

/** The last install stage named in [text], if any. */
export function lastInstallStage(text: string): string | undefined {
  let stage: string | undefined;
  for (const match of text.matchAll(STAGE_PATTERN)) stage = match[1];
  return stage;
}

type InstallProgressOverlayProps = Readonly<{
  /** Undefined for an app that is already installed and only starting. */
  step?: number;
  toolchain: TerminalToolchainTarget;
}>;

/**
 * Covers the terminal while an app installs (four steps) or starts (one
 * line). The screen draws the SHOW LOG / SHOW PROGRESS toggle above it, so
 * both labels sit in the same spot.
 */
export function InstallProgressOverlay({step, toolchain}: InstallProgressOverlayProps): React.JSX.Element {
  const label = toolchainInstallLabel(toolchain);
  const steps = ['Preparing workspace', 'Installing system packages', `Installing ${label}`, `Starting ${label}`];
  if (step === undefined) {
    return (
      <View style={styles.overlay} testID="terminal-startup-progress">
        <View style={styles.content}>
          <HarnessMark size={56} toolchain={toolchain} />
          <Text style={styles.title}>Starting {label}</Text>
          <ActivityIndicator color={uiColors.accent} style={styles.spinner} />
        </View>
      </View>
    );
  }
  return (
    <View style={styles.overlay} testID="terminal-install-progress">
      <View style={styles.content}>
        <HarnessMark size={56} toolchain={toolchain} />
        <Text style={styles.title}>Setting up {label}</Text>
        <Text style={styles.subtitle}>First launch only. This can take a few minutes on a slow connection.</Text>
        <View accessibilityLiveRegion="polite" style={styles.steps}>
          {steps.map((text, index) => {
            const done = index < step;
            const active = index === step;
            return (
              <View key={text} style={styles.stepRow} testID={`terminal-install-step-${index}`}>
                <View style={[styles.marker, done && styles.markerDone, active && styles.markerActive]}>
                  {done ? <View style={styles.check} /> : active ? <ActivityIndicator color={uiColors.accent} size="small" /> : null}
                </View>
                <Text style={[styles.stepText, done && styles.stepTextDone, active && styles.stepTextActive]}>{text}</Text>
              </View>
            );
          })}
        </View>
      </View>
    </View>
  );
}

const styles = StyleSheet.create({
  overlay: {backgroundColor: uiColors.background, bottom: 0, justifyContent: 'center', left: 0, paddingHorizontal: 28, position: 'absolute', right: 0, top: 0, zIndex: 4},
  content: {alignItems: 'center'},
  title: {color: uiColors.ink, fontFamily: UI_FONT_FAMILY, fontSize: 18, fontWeight: '800', marginTop: 18, textAlign: 'center'},
  subtitle: {color: uiColors.muted, fontFamily: UI_FONT_FAMILY, fontSize: 10, lineHeight: 16, marginTop: 8, maxWidth: 300, textAlign: 'center'},
  steps: {alignSelf: 'stretch', gap: 14, marginTop: 28, maxWidth: 320, width: '100%', marginHorizontal: 'auto'},
  stepRow: {alignItems: 'center', flexDirection: 'row', gap: 12},
  marker: {alignItems: 'center', borderColor: uiColors.border, borderRadius: 12, borderWidth: 1, height: 24, justifyContent: 'center', width: 24},
  markerDone: {backgroundColor: uiColors.accent, borderColor: uiColors.accent},
  markerActive: {borderColor: uiColors.accent},
  // Drawn, not a ✓ character: the UI font has no check-mark glyph.
  check: {borderBottomWidth: 2.5, borderColor: uiColors.background, borderRightWidth: 2.5, height: 11, marginTop: -3, transform: [{rotate: '45deg'}], width: 6},
  stepText: {color: uiColors.subdued, fontFamily: UI_FONT_FAMILY, fontSize: 12},
  stepTextDone: {color: uiColors.muted},
  stepTextActive: {color: uiColors.ink, fontWeight: '800'},
  spinner: {marginTop: 18},
});
