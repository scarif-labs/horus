import React from 'react';
import {ActivityIndicator, Linking, StyleSheet, Text, View} from 'react-native';
import {PINNED_ROOTFS_URL} from '../terminal/distroContract';
import {UI_FONT_FAMILY} from './typography';
import {AuthScreenLayout, authStyles} from './AuthScreenLayout';
import {uiColors} from './brand';
import {InteractivePressable as Pressable} from './InteractivePressable';

export type LoadingScreenProps = Readonly<{
  status: 'checking' | 'installing' | 'error';
  detail?: string;
  onRetry: () => void;
  /** Picks a rootfs archive the user downloaded in a browser. */
  onImport?: () => void;
  onDebugTerminal?: () => void;
}>;

// Errors where fetching the archive by hand can get the user unstuck.
const MANUAL_DOWNLOAD_ERRORS = new Set(['download_failed', 'import_failed', 'import_cancelled', 'digest_mismatch']);

export function LoadingScreen({status, detail, onRetry, onImport, onDebugTerminal}: LoadingScreenProps): React.JSX.Element {
  const isError = status === 'error';
  const showManualDownload = isError && onImport !== undefined && detail !== undefined && MANUAL_DOWNLOAD_ERRORS.has(detail);
  return (
    <AuthScreenLayout brandTestID="loading" screenTestID="loading-screen">
      <View accessibilityLiveRegion="polite" style={authStyles.card}>
        <Text style={styles.kicker}>LOCAL RUNTIME · ARM64</Text>
        <Text style={styles.title}>Booting your workspace</Text>
        <Text style={styles.body}>
          {showManualDownload
            ? 'Alpine could not be downloaded automatically.'
            : isError
            ? 'The Alpine guest could not be prepared yet.'
            : status === 'installing'
              ? 'Preparing the Alpine guest. This may take a few minutes.'
              : 'Checking the local Alpine runtime.'}
        </Text>
        <View style={styles.statusRow}>
          {!isError
            ? <ActivityIndicator color={uiColors.accent} size="small" />
            : <View style={showManualDownload ? styles.warningDot : styles.errorDot} />}
          <Text style={[styles.status, isError && (showManualDownload ? styles.statusWarning : styles.statusError)]} testID="loading-status">
            {isError ? `runtime=${detail ?? 'unavailable'}` : status === 'installing' ? 'Preparing Alpine…' : 'Checking runtime…'}
          </Text>
        </View>
        {showManualDownload ? (
          <View style={styles.manual} testID="loading-manual-download">
            <Text style={styles.body}>
              {detail === 'import_failed' || detail === 'digest_mismatch'
                ? 'That file is not the Alpine archive Horus expects. Download it again from the link below and import it.'
                : 'You can download it in your browser instead, then import the file here. Horus checks its SHA-256 before installing it.'}
            </Text>
            <Text selectable style={styles.url} testID="loading-rootfs-url">{PINNED_ROOTFS_URL}</Text>
            <Pressable
              accessibilityRole="button"
              onPress={() => { void Linking.openURL(PINNED_ROOTFS_URL).catch(() => undefined); }}
              style={styles.secondaryButton}
              testID="loading-open-rootfs-url">
              <Text style={styles.secondaryButtonText}>OPEN IN BROWSER</Text>
            </Pressable>
            <Pressable accessibilityRole="button" onPress={onImport} style={styles.secondaryButton} testID="loading-import-rootfs">
              <Text style={styles.secondaryButtonText}>IMPORT DOWNLOADED FILE</Text>
            </Pressable>
          </View>
        ) : null}
        {isError ? (
          <Pressable accessibilityRole="button" onPress={onRetry} style={authStyles.button} testID="loading-retry">
            <Text style={authStyles.buttonText}>TRY AGAIN  →</Text>
          </Pressable>
        ) : null}
        {onDebugTerminal !== undefined ? (
          <Pressable accessibilityRole="button" onPress={onDebugTerminal} style={styles.debug} testID="loading-debug-terminal">
            <Text style={styles.debugText}>OPEN TERMINAL / DEBUG</Text>
          </Pressable>
        ) : null}
      </View>
    </AuthScreenLayout>
  );
}

const styles = StyleSheet.create({
  kicker: {color: uiColors.muted, fontFamily: UI_FONT_FAMILY, fontSize: 9, letterSpacing: 0.8},
  title: {color: uiColors.ink, fontFamily: UI_FONT_FAMILY, fontSize: 18, fontWeight: '700', lineHeight: 26, marginTop: 8},
  body: {color: uiColors.muted, fontFamily: UI_FONT_FAMILY, fontSize: 12, lineHeight: 19, marginTop: 8},
  statusRow: {alignItems: 'center', borderTopColor: uiColors.border, borderTopWidth: 1, flexDirection: 'row', gap: 10, marginTop: 20, paddingTop: 14},
  status: {color: uiColors.accent, fontFamily: UI_FONT_FAMILY, fontSize: 11},
  statusError: {color: uiColors.danger},
  errorDot: {backgroundColor: uiColors.danger, borderRadius: 4, height: 8, width: 8},
  statusWarning: {color: uiColors.warning},
  warningDot: {backgroundColor: uiColors.warning, borderRadius: 4, height: 8, width: 8},
  manual: {borderTopColor: uiColors.border, borderTopWidth: 1, marginTop: 14, paddingTop: 2},
  url: {backgroundColor: uiColors.background, borderColor: uiColors.borderSoft, borderRadius: 8, borderWidth: 1, color: uiColors.ink, fontFamily: UI_FONT_FAMILY, fontSize: 10, lineHeight: 16, marginTop: 10, padding: 10},
  secondaryButton: {alignItems: 'center', borderColor: uiColors.accent, borderRadius: 8, borderWidth: 1, justifyContent: 'center', marginTop: 10, minHeight: 46},
  secondaryButtonText: {color: uiColors.accent, fontFamily: UI_FONT_FAMILY, fontSize: 10, fontWeight: '800', letterSpacing: 0.5},
  debug: {alignItems: 'center', borderColor: uiColors.border, borderRadius: 8, borderWidth: 1, justifyContent: 'center', marginTop: 10, minHeight: 42},
  debugText: {color: uiColors.muted, fontFamily: UI_FONT_FAMILY, fontSize: 9, fontWeight: '800', letterSpacing: 0.5},
});
