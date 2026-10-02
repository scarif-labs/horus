import React from 'react';
import {StyleSheet, Text} from 'react-native';
import {uiColors} from './brand';
import {SettingsAction, SettingsRow, SettingsSection, SettingsSwitch} from './SettingsList';
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
  const computers = snapshot?.computers ?? [];
  return (
    <SettingsSection
      footer={error && snapshot !== undefined ? 'Could not update remote access. Try again.' : 'Optional. Run `horus pair` on a computer connected over USB to get a shell here over SSH. Only computers paired with your Horus password can log in, and nothing listens on Wi-Fi.'}
      footerTone={error && snapshot !== undefined ? 'danger' : 'muted'}
      testID="remote-access-panel"
      title="REMOTE ACCESS">
      <SettingsRow
        below={<Text style={styles.status} testID="remote-access-status">{snapshot === undefined ? (error ? 'Unavailable' : 'Loading…') : remoteAccessStatusLabel(snapshot)}</Text>}
        icon="laptop"
        label="Use from a computer"
        right={(
          <SettingsSwitch
            accessibilityLabel={enabled ? 'Turn off remote access' : 'Turn on remote access'}
            disabled={snapshot === undefined || busy}
            onPress={() => { void run(() => setEnabled(!enabled)); }}
            testID="remote-access-toggle"
            value={enabled}
          />
        )}
      />
      {computers.map(computer => (
        <SettingsRow
          detail={computer.fingerprint}
          key={computer.fingerprint}
          label={computer.label === '' ? 'Unnamed key' : computer.label}
          right={(
            <SettingsAction
              accessibilityLabel={`Revoke ${computer.label}`}
              disabled={busy}
              label="REVOKE"
              onPress={() => { void run(() => revoke(computer.fingerprint)); }}
              testID="remote-access-revoke"
              tone="danger"
            />
          )}
          testID="remote-access-computer"
        />
      ))}
    </SettingsSection>
  );
}

const styles = StyleSheet.create({
  status: {color: uiColors.subdued, fontFamily: UI_FONT_FAMILY, fontSize: 9, lineHeight: 13, marginLeft: 32, marginTop: 4},
});
