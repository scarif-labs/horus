import React from 'react';
import {ActivityIndicator, StyleSheet, Text, View} from 'react-native';
import type {TerminalToolchainTarget} from '../native/NativeTerminalRuntime';
import {InteractivePressable as Pressable} from '../ui/InteractivePressable';
import {UI_FONT_FAMILY} from '../ui/typography';
import {BrandMark} from './BrandMark';
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
  step: number;
  toolchain: TerminalToolchainTarget;
  onShowLog: () => void;
}>;

export function InstallProgressOverlay({step, toolchain, onShowLog}: InstallProgressOverlayProps): React.JSX.Element {
  const label = toolchainInstallLabel(toolchain);
  const steps = ['Preparing workspace', 'Installing system packages', `Installing ${label}`, `Starting ${label}`];
  return (
    <View style={styles.overlay} testID="terminal-install-progress">
      <View style={styles.content}>
        <BrandMark accessible={false} size={56} />
        <Text style={styles.title}>Setting up {label}</Text>
        <Text style={styles.subtitle}>First launch only. This can take a few minutes on a slow connection.</Text>
        <View accessibilityLiveRegion="polite" style={styles.steps}>
          {steps.map((text, index) => {
            const done = index < step;
            const active = index === step;
            return (
              <View key={text} style={styles.stepRow} testID={`terminal-install-step-${index}`}>
                <View style={[styles.marker, done && styles.markerDone, active && styles.markerActive]}>
                  {done ? <Text style={styles.check}>✓</Text> : active ? <ActivityIndicator color={uiColors.accent} size="small" /> : null}
                </View>
                <Text style={[styles.stepText, done && styles.stepTextDone, active && styles.stepTextActive]}>{text}</Text>
              </View>
            );
          })}
        </View>
      </View>
      <Pressable accessibilityLabel="Show install log" accessibilityRole="button" onPress={onShowLog} style={styles.logButton} testID="terminal-install-show-log">
        <Text style={styles.logButtonText}>SHOW LOG</Text>
      </Pressable>
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
  check: {color: uiColors.background, fontFamily: UI_FONT_FAMILY, fontSize: 12, fontWeight: '900', includeFontPadding: false},
  stepText: {color: uiColors.subdued, fontFamily: UI_FONT_FAMILY, fontSize: 12},
  stepTextDone: {color: uiColors.muted},
  stepTextActive: {color: uiColors.ink, fontWeight: '800'},
  logButton: {alignItems: 'center', alignSelf: 'center', borderColor: uiColors.border, borderRadius: 8, borderWidth: 1, bottom: 24, justifyContent: 'center', minHeight: 36, paddingHorizontal: 16, position: 'absolute'},
  logButtonText: {color: uiColors.muted, fontFamily: UI_FONT_FAMILY, fontSize: 9, fontWeight: '800', letterSpacing: 0.6},
});
