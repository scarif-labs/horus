import React from 'react';
import {StyleSheet, Text, View} from 'react-native';
import {uiColors} from './brand';
import {InteractivePressable as Pressable} from './InteractivePressable';
import {UI_FONT_FAMILY} from './typography';
import {
  readRemoteAccess,
  remoteAccessStatusLabel,
  revokeRemoteComputer,
  setRemoteAccessEnabled,
  type RemoteAccessResult,
  type RemoteAccessSnapshot,
} from '../remote/remoteAccess';

type RemoteAccessPanelProps = Readonly<{
  read?: () => Promise<RemoteAccessResult>;
  setEnabled?: (enabled: boolean) => Promise<RemoteAccessResult>;
  revoke?: (fingerprint: string) => Promise<RemoteAccessResult>;
}>;

const TRANSITION_POLL_MS = 1500;

export function RemoteAccessPanel({
  read = readRemoteAccess,
  setEnabled = setRemoteAccessEnabled,
  revoke = revokeRemoteComputer,
}: RemoteAccessPanelProps): React.JSX.Element {
  const [snapshot, setSnapshot] = React.useState<RemoteAccessSnapshot | undefined>();
  const [busy, setBusy] = React.useState(false);
  const [error, setError] = React.useState(false);
  const mountedRef = React.useRef(true);

  const apply = React.useCallback((result: RemoteAccessResult) => {
    if (!mountedRef.current) return;
    if (result.kind === 'success') {
      setSnapshot(result.snapshot);
      setError(false);
    } else {
      setError(true);
    }
  }, []);

  React.useEffect(() => {
    mountedRef.current = true;
    read().then(apply).catch(() => undefined);
    return () => {
      mountedRef.current = false;
    };
  }, [apply, read]);

  // Follow the server while it installs or starts; stay quiet otherwise.
  const transitioning = snapshot?.enabled === true && (snapshot.state === 'starting' || snapshot.state === 'installing' || snapshot.state === 'stopped');
  React.useEffect(() => {
    if (!transitioning) return undefined;
    const timer = setInterval(() => {
      read().then(apply).catch(() => undefined);
    }, TRANSITION_POLL_MS);
    return () => clearInterval(timer);
  }, [apply, read, transitioning]);

  const run = React.useCallback(async (action: () => Promise<RemoteAccessResult>) => {
    if (busy) return;
    setBusy(true);
    apply(await action());
    if (mountedRef.current) setBusy(false);
  }, [apply, busy]);

  const enabled = snapshot?.enabled === true;
  return (
    <View style={styles.panel} testID="remote-access-panel">
      <Text style={styles.sectionLabel}>REMOTE ACCESS</Text>
      <Text style={styles.title}>Use this phone from a computer</Text>
      <Text style={styles.detail}>
        Optional. Connect the phone over USB and run `horus pair` on your computer to get a shell here over SSH.
        Only computers you pair with your Horus password can log in, and nothing listens on Wi-Fi.
      </Text>
      <Pressable
        accessibilityLabel={enabled ? 'Turn off remote access' : 'Turn on remote access'}
        accessibilityRole="switch"
        accessibilityState={{checked: enabled, disabled: snapshot === undefined || busy}}
        disabled={snapshot === undefined || busy}
        onPress={() => { void run(() => setEnabled(!enabled)); }}
        style={[styles.toggle, enabled && styles.toggleOn, (snapshot === undefined || busy) && styles.disabled]}
        testID="remote-access-toggle">
        <Text style={[styles.toggleText, enabled && styles.toggleTextOn]}>{enabled ? 'ON' : 'OFF'}</Text>
      </Pressable>
      <Text style={styles.status} testID="remote-access-status">
        {snapshot === undefined ? (error ? 'Unavailable' : 'Loading…') : remoteAccessStatusLabel(snapshot)}
      </Text>
      {snapshot === undefined || snapshot.computers.length === 0 ? null : (
        <View style={styles.computers}>
          <Text style={styles.sectionLabel}>PAIRED COMPUTERS</Text>
          {snapshot.computers.map(computer => (
            <View key={computer.fingerprint} style={styles.computer} testID="remote-access-computer">
              <View style={styles.computerText}>
                <Text numberOfLines={1} style={styles.computerLabel}>{computer.label === '' ? 'Unnamed key' : computer.label}</Text>
                <Text numberOfLines={1} style={styles.fingerprint}>{computer.fingerprint}</Text>
              </View>
              <Pressable
                accessibilityLabel={`Revoke ${computer.label}`}
                accessibilityRole="button"
                disabled={busy}
                onPress={() => { void run(() => revoke(computer.fingerprint)); }}
                style={[styles.revoke, busy && styles.disabled]}
                testID="remote-access-revoke">
                <Text style={styles.revokeText}>REVOKE</Text>
              </Pressable>
            </View>
          ))}
        </View>
      )}
      {error && snapshot !== undefined ? <Text style={styles.error}>Could not update remote access. Try again.</Text> : null}
    </View>
  );
}

const styles = StyleSheet.create({
  panel: {backgroundColor: uiColors.panel, borderColor: uiColors.border, borderRadius: 14, borderWidth: 1, marginTop: 14, padding: 15},
  sectionLabel: {color: uiColors.muted, fontFamily: UI_FONT_FAMILY, fontSize: 10, letterSpacing: 0.8},
  title: {color: uiColors.ink, fontFamily: UI_FONT_FAMILY, fontSize: 17, fontWeight: '800', marginTop: 12},
  detail: {color: uiColors.muted, fontFamily: UI_FONT_FAMILY, fontSize: 10, lineHeight: 17, marginTop: 8},
  toggle: {alignItems: 'center', backgroundColor: uiColors.background, borderColor: uiColors.border, borderRadius: 10, borderWidth: 1, justifyContent: 'center', marginTop: 16, minHeight: 48},
  toggleOn: {backgroundColor: uiColors.accent, borderColor: uiColors.accent},
  toggleText: {color: uiColors.ink, fontFamily: UI_FONT_FAMILY, fontSize: 13, fontWeight: '800', letterSpacing: 0.8},
  toggleTextOn: {color: uiColors.background},
  disabled: {opacity: 0.55},
  status: {color: uiColors.subdued, fontFamily: UI_FONT_FAMILY, fontSize: 10, lineHeight: 16, marginTop: 12},
  computers: {marginTop: 16},
  computer: {alignItems: 'center', borderColor: uiColors.border, borderTopWidth: 1, flexDirection: 'row', gap: 10, marginTop: 10, paddingTop: 10},
  computerText: {flex: 1},
  computerLabel: {color: uiColors.ink, fontFamily: UI_FONT_FAMILY, fontSize: 12, fontWeight: '700'},
  fingerprint: {color: uiColors.muted, fontFamily: UI_FONT_FAMILY, fontSize: 9, marginTop: 3},
  revoke: {alignItems: 'center', borderColor: uiColors.danger, borderRadius: 7, borderWidth: 1, justifyContent: 'center', minHeight: 32, paddingHorizontal: 10},
  revokeText: {color: uiColors.danger, fontFamily: UI_FONT_FAMILY, fontSize: 9, fontWeight: '800', letterSpacing: 0.5},
  error: {color: uiColors.danger, fontFamily: UI_FONT_FAMILY, fontSize: 10, marginTop: 12},
});
