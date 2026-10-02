import React from 'react';
import {ActivityIndicator, StyleSheet, Text, View} from 'react-native';
import type {TerminalToolchainTarget} from '../native/NativeTerminalRuntime';
import {UI_FONT_FAMILY} from '../ui/typography';
import {InteractivePressable as Pressable} from '../ui/InteractivePressable';
import {HarnessMark} from './harnessLogos';
import type {TerminalSessionClient} from './session/sessionClient';
import type {ActiveTerminalSession} from './session/sessionContract';
import {formatSessionAge, sessionTitle, toolchainInstallLabel} from './toolchainLabels';
import {nextRequestId} from './terminalRequestId';
import {readSessionSettings, type SessionSettingsResult} from './session/sessionSettings';
import {uiColors} from './palette';

type SessionLimitOverlayProps = Readonly<{
  client: TerminalSessionClient;
  /** Whether the session limit was reached; the overlay renders nothing otherwise. */
  visible: boolean;
  /** The app this screen is trying to open. */
  toolchain: TerminalToolchainTarget;
  /** Called after the user closes a session, to retry starting this one. */
  onSessionFreed: () => Promise<void>;
  onBack?: () => void;
  readSettings?: () => Promise<SessionSettingsResult>;
}>;

/**
 * Shown instead of an app when the session limit is full: lists what is
 * running so the user can close one, and this app then opens.
 */
export function SessionLimitOverlay({client, visible, toolchain, onSessionFreed, onBack, readSettings = readSessionSettings}: SessionLimitOverlayProps): React.JSX.Element | null {
  const [sessions, setSessions] = React.useState<readonly ActiveTerminalSession[]>([]);
  const [loading, setLoading] = React.useState(false);
  const [closingId, setClosingId] = React.useState<string | undefined>();
  const [failed, setFailed] = React.useState(false);
  const [limit, setLimit] = React.useState<number | undefined>();
  const mountedRef = React.useRef(true);

  React.useEffect(() => {
    mountedRef.current = true;
    return () => {
      mountedRef.current = false;
    };
  }, []);

  React.useEffect(() => {
    if (!visible) {
      setSessions([]);
      setLoading(false);
      setClosingId(undefined);
      setFailed(false);
      return;
    }
    let cancelled = false;
    readSettings().then(result => {
      if (!cancelled && mountedRef.current && result.kind === 'success') setLimit(result.settings.maxConcurrentSessions);
    }).catch(() => undefined);
    setLoading(true);
    setFailed(false);
    void client.listTerminalSessions(nextRequestId('limit-sessions')).then(result => {
      if (cancelled || !mountedRef.current) return;
      if (result.kind === 'success') setSessions(result.sessions);
      else setFailed(true);
      setLoading(false);
    }).catch(() => {
      if (cancelled || !mountedRef.current) return;
      setFailed(true);
      setLoading(false);
    });
    return () => {
      cancelled = true;
    };
  }, [client, readSettings, visible]);

  const closeSession = React.useCallback(async (sessionId: string) => {
    if (closingId !== undefined) return;
    setClosingId(sessionId);
    setFailed(false);
    const result = await client.stopSession(nextRequestId('limit-stop'), sessionId, 'user_stop');
    if (!mountedRef.current) return;
    if (result.kind !== 'success') {
      setClosingId(undefined);
      setFailed(true);
      return;
    }
    setSessions(current => current.filter(session => session.sessionId !== sessionId));
    setClosingId(undefined);
    await onSessionFreed();
  }, [client, closingId, onSessionFreed]);

  if (!visible) return null;
  const label = toolchainInstallLabel(toolchain);
  return (
    <View pointerEvents="auto" style={styles.overlay} testID="session-limit-warning-overlay">
      <View style={styles.content} testID="session-limit-warning">
        <HarnessMark size={48} toolchain={toolchain} />
        <Text style={styles.title} testID="session-limit-warning-title">Close an app to open {label}</Text>
        <Text style={styles.detail} testID="session-limit-warning-message">
          {limit === undefined
            ? 'You can change how many run at once in Settings.'
            : `Horus runs up to ${limit === 1 ? '1 app' : `${limit} apps`} at once. You can change this in Settings.`}
        </Text>
        <View style={styles.list} testID="session-limit-sessions">
          {loading ? <ActivityIndicator color={uiColors.accent} style={styles.loading} testID="session-limit-sessions-loading" /> : null}
          {sessions.map(session => {
            const closing = closingId === session.sessionId;
            const title = sessionTitle(session);
            return (
              <View key={session.sessionId} style={styles.row} testID={`session-limit-session-${session.sessionId}`}>
                <HarnessMark size={28} toolchain={session.toolchain} />
                <View style={styles.rowCopy}>
                  <Text numberOfLines={1} style={styles.rowTitle} testID={`session-limit-session-title-${session.sessionId}`}>{title}</Text>
                  <Text numberOfLines={1} style={styles.rowMeta} testID={`session-limit-session-meta-${session.sessionId}`}>Running {formatSessionAge(session.startedAtMs)}</Text>
                </View>
                <Pressable
                  accessibilityLabel={`Close ${title}`}
                  accessibilityRole="button"
                  accessibilityState={{busy: closing, disabled: closingId !== undefined}}
                  disabled={closingId !== undefined}
                  onPress={() => { void closeSession(session.sessionId); }}
                  style={[styles.close, closingId !== undefined && !closing && styles.closeDisabled]}
                  testID={`session-limit-terminate-${session.sessionId}`}>
                  <Text style={styles.closeText}>{closing ? 'CLOSING…' : 'CLOSE'}</Text>
                </Pressable>
              </View>
            );
          })}
        </View>
        {failed ? <Text style={styles.error} testID="session-limit-action-error">That didn’t work. Try again.</Text> : null}
        {onBack === undefined ? null : (
          <Pressable accessibilityRole="button" onPress={onBack} style={styles.back} testID="session-limit-back">
            <Text style={styles.backText}>BACK</Text>
          </Pressable>
        )}
      </View>
    </View>
  );
}

const styles = StyleSheet.create({
  overlay: {backgroundColor: uiColors.background, bottom: 0, justifyContent: 'center', left: 0, paddingHorizontal: 24, position: 'absolute', right: 0, top: 0, zIndex: 5, elevation: 5},
  content: {alignItems: 'center', alignSelf: 'center', maxWidth: 420, width: '100%'},
  title: {color: uiColors.ink, fontFamily: UI_FONT_FAMILY, fontSize: 18, fontWeight: '800', marginTop: 16, textAlign: 'center'},
  detail: {color: uiColors.muted, fontFamily: UI_FONT_FAMILY, fontSize: 11, lineHeight: 17, marginTop: 8, textAlign: 'center'},
  list: {alignSelf: 'stretch', gap: 8, marginTop: 24, minHeight: 60},
  loading: {marginTop: 18},
  row: {alignItems: 'center', backgroundColor: uiColors.panel, borderColor: uiColors.border, borderRadius: 10, borderWidth: 1, flexDirection: 'row', gap: 12, minHeight: 60, paddingHorizontal: 12},
  rowCopy: {flex: 1, minWidth: 0},
  rowTitle: {color: uiColors.ink, fontFamily: UI_FONT_FAMILY, fontSize: 13, fontWeight: '700'},
  rowMeta: {color: uiColors.muted, fontFamily: UI_FONT_FAMILY, fontSize: 10, marginTop: 3},
  close: {alignItems: 'center', borderColor: uiColors.border, borderRadius: 8, borderWidth: 1, justifyContent: 'center', minHeight: 36, minWidth: 76, paddingHorizontal: 10},
  closeDisabled: {opacity: 0.5},
  closeText: {color: uiColors.ink, fontFamily: UI_FONT_FAMILY, fontSize: 10, fontWeight: '800', letterSpacing: 0.5},
  error: {color: uiColors.danger, fontFamily: UI_FONT_FAMILY, fontSize: 10, marginTop: 12, textAlign: 'center'},
  back: {alignItems: 'center', alignSelf: 'stretch', borderColor: uiColors.border, borderRadius: 10, borderWidth: 1, justifyContent: 'center', marginTop: 20, minHeight: 42},
  backText: {color: uiColors.ink, fontFamily: UI_FONT_FAMILY, fontSize: 11, fontWeight: '800', letterSpacing: 0.6},
});
