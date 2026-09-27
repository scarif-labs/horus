import React from 'react';
import {ActivityIndicator, StyleSheet, Text, View} from 'react-native';
import {UI_FONT_FAMILY} from './typography';
import {AuthScreenLayout, authStyles} from './AuthScreenLayout';
import {uiColors} from './brand';
import {InteractivePressable as Pressable} from './InteractivePressable';

export type LoadingScreenProps = Readonly<{
  status: 'checking' | 'installing' | 'error';
  detail?: string;
  onRetry: () => void;
  onDebugTerminal?: () => void;
}>;

export function LoadingScreen({status, detail, onRetry, onDebugTerminal}: LoadingScreenProps): React.JSX.Element {
  const isError = status === 'error';
  return (
    <AuthScreenLayout brandTestID="loading" screenTestID="loading-screen">
      <View accessibilityLiveRegion="polite" style={authStyles.card}>
        <Text style={styles.kicker}>LOCAL RUNTIME · ARM64</Text>
        <Text style={styles.title}>Booting your workspace</Text>
        <Text style={styles.body}>
          {isError
            ? 'The Alpine guest could not be prepared yet.'
            : status === 'installing'
              ? 'Preparing the Alpine guest. This may take a few minutes.'
              : 'Checking the local Alpine runtime.'}
        </Text>
        <View style={styles.statusRow}>
          {!isError ? <ActivityIndicator color={uiColors.accent} size="small" /> : <View style={styles.errorDot} />}
          <Text style={[styles.status, isError && styles.statusError]} testID="loading-status">
            {isError ? `runtime=${detail ?? 'unavailable'}` : status === 'installing' ? 'Preparing Alpine…' : 'Checking runtime…'}
          </Text>
        </View>
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
  debug: {alignItems: 'center', borderColor: uiColors.border, borderRadius: 8, borderWidth: 1, justifyContent: 'center', marginTop: 10, minHeight: 42},
  debugText: {color: uiColors.muted, fontFamily: UI_FONT_FAMILY, fontSize: 9, fontWeight: '800', letterSpacing: 0.5},
});
