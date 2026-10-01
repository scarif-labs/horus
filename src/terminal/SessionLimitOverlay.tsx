import React from 'react';
import {StyleSheet, Text, View} from 'react-native';
import {UI_FONT_FAMILY} from '../ui/typography';
import {InteractivePressable as Pressable} from '../ui/InteractivePressable';
import type {TerminalSessionClient} from './session/sessionClient';
import type {ActiveTerminalSession} from './session/sessionContract';
import {TERMINAL_FOREGROUND} from './terminalBuffer';
import {formatSessionAge, sessionTitle} from './toolchainLabels';
import {nextRequestId} from './terminalRequestId';
import {uiColors} from './palette';

type SessionLimitOverlayProps = Readonly<{
  client: TerminalSessionClient;
  /** Whether the session limit was reached; the overlay renders nothing otherwise. */
  visible: boolean;
  message: string;
  /** Called after the user stops a session, to retry starting this one. */
  onSessionFreed: () => Promise<void>;
}>;

export function SessionLimitOverlay({client, visible, message, onSessionFreed}: SessionLimitOverlayProps): React.JSX.Element | null {
  const [limitSessions, setLimitSessions] = React.useState<readonly ActiveTerminalSession[]>([]);
  const [limitSessionsLoading, setLimitSessionsLoading] = React.useState(false);
  const [terminatingLimitSessionId, setTerminatingLimitSessionId] = React.useState<string | undefined>();
  const [limitSessionActionError, setLimitSessionActionError] = React.useState(false);
  const mountedRef = React.useRef(true);

  React.useEffect(() => {
    mountedRef.current = true;
    return () => {
      mountedRef.current = false;
    };
  }, []);

  React.useEffect(() => {
    if (!visible) {
      setLimitSessions([]);
      setLimitSessionsLoading(false);
      setTerminatingLimitSessionId(undefined);
      setLimitSessionActionError(false);
      return;
    }
    let cancelled = false;
    setLimitSessionsLoading(true);
    setLimitSessionActionError(false);
    void client.listTerminalSessions(nextRequestId('limit-sessions')).then(result => {
      if (cancelled || !mountedRef.current) return;
      if (result.kind === 'success') setLimitSessions(result.sessions);
      else setLimitSessionActionError(true);
      setLimitSessionsLoading(false);
    }).catch(() => {
      if (cancelled || !mountedRef.current) return;
      setLimitSessionActionError(true);
      setLimitSessionsLoading(false);
    });
    return () => {
      cancelled = true;
    };
  }, [client, visible]);

  const terminateLimitSession = React.useCallback(async (sessionId: string) => {
    if (terminatingLimitSessionId !== undefined) return;
    setTerminatingLimitSessionId(sessionId);
    setLimitSessionActionError(false);
    const result = await client.stopSession(nextRequestId('limit-stop'), sessionId, 'user_stop');
    if (!mountedRef.current) return;
    if (result.kind !== 'success') {
      setTerminatingLimitSessionId(undefined);
      setLimitSessionActionError(true);
      return;
    }
    setLimitSessions(current => current.filter(session => session.sessionId !== sessionId));
    setTerminatingLimitSessionId(undefined);
    await onSessionFreed();
  }, [client, onSessionFreed, terminatingLimitSessionId]);

  if (!visible) return null;
  return (
    <View pointerEvents="auto" style={styles.sessionLimitOverlay} testID="session-limit-warning-overlay">
      <View style={styles.sessionLimitCard} testID="session-limit-warning">
        <Text style={styles.sessionLimitTitle} testID="session-limit-warning-title">Too many apps running</Text>
        <Text style={styles.sessionLimitMessage} testID="session-limit-warning-message">
          {message}
        </Text>
        <Text style={styles.sessionLimitDetails} testID="session-limit-warning-settings-hint">
          You can change the limit in Settings under Concurrent apps.
        </Text>
        {limitSessionsLoading ? <Text style={styles.sessionLimitDetails} testID="session-limit-sessions-loading">Loading active sessions…</Text> : null}
        {!limitSessionsLoading && limitSessions.length === 0 && !limitSessionActionError ? (
          <Text style={styles.sessionLimitDetails} testID="session-limit-no-sessions">No active session details are available.</Text>
        ) : null}
        {!limitSessionsLoading && limitSessions.length > 0 ? (
          <View style={styles.sessionLimitSessions} testID="session-limit-sessions">
            {limitSessions.map(session => {
              const stopping = terminatingLimitSessionId === session.sessionId;
              const title = sessionTitle(session);
              return (
                <View key={session.sessionId} style={styles.sessionLimitSessionRow} testID={`session-limit-session-${session.sessionId}`}>
                  <View style={styles.sessionLimitSessionCopy}>
                    <Text numberOfLines={1} style={styles.sessionLimitSessionTitle} testID={`session-limit-session-title-${session.sessionId}`}>{title}</Text>
                    <Text numberOfLines={1} style={styles.sessionLimitSessionMeta} testID={`session-limit-session-meta-${session.sessionId}`}>ACTIVE PTY · {formatSessionAge(session.startedAtMs)}</Text>
                  </View>
                  <Pressable
                    accessibilityLabel={`Terminate ${title}`}
                    accessibilityRole="button"
                    accessibilityState={{disabled: terminatingLimitSessionId !== undefined}}
                    disabled={terminatingLimitSessionId !== undefined}
                    onPress={() => { void terminateLimitSession(session.sessionId); }}
                    style={[styles.sessionLimitTerminate, stopping && styles.sessionLimitTerminateDisabled]}
                    testID={`session-limit-terminate-${session.sessionId}`}>
                    <Text style={styles.sessionLimitTerminateText}>{stopping ? 'STOPPING' : 'TERMINATE'}</Text>
                  </Pressable>
                </View>
              );
            })}
          </View>
        ) : null}
        {limitSessionActionError ? <Text style={styles.sessionLimitActionError} testID="session-limit-action-error">Could not load or stop the active session. Try again from the menu.</Text> : null}
      </View>
    </View>
  );
}

const styles = StyleSheet.create({
  sessionLimitOverlay: {alignItems: 'center', backgroundColor: 'rgba(13, 17, 18, 0.82)', bottom: 0, justifyContent: 'center', left: 0, padding: 18, position: 'absolute', right: 0, top: 0, zIndex: 5},
  sessionLimitCard: {backgroundColor: '#182022', borderColor: uiColors.danger, borderRadius: 12, borderWidth: 1, maxWidth: 520, paddingHorizontal: 20, paddingVertical: 18, width: '100%'},
  sessionLimitTitle: {color: uiColors.danger, fontFamily: UI_FONT_FAMILY, fontSize: 17, fontWeight: '800', textAlign: 'center'},
  sessionLimitMessage: {color: TERMINAL_FOREGROUND, fontFamily: UI_FONT_FAMILY, fontSize: 13, lineHeight: 19, marginTop: 10, textAlign: 'center'},
  sessionLimitDetails: {color: uiColors.muted, fontFamily: UI_FONT_FAMILY, fontSize: 10, lineHeight: 16, marginTop: 14, textAlign: 'center'},
  sessionLimitSessions: {gap: 8, marginTop: 14},
  sessionLimitSessionRow: {alignItems: 'center', backgroundColor: uiColors.panel, borderColor: uiColors.border, borderRadius: 8, borderWidth: 1, flexDirection: 'row', minHeight: 58, paddingHorizontal: 9, paddingVertical: 7},
  sessionLimitSessionCopy: {flex: 1, minWidth: 0},
  sessionLimitSessionTitle: {color: TERMINAL_FOREGROUND, fontFamily: UI_FONT_FAMILY, fontSize: 12, fontWeight: '800'},
  sessionLimitSessionMeta: {color: uiColors.muted, fontFamily: UI_FONT_FAMILY, fontSize: 8, letterSpacing: 0.2, marginTop: 4},
  sessionLimitTerminate: {alignItems: 'center', borderColor: uiColors.danger, borderRadius: 6, borderWidth: 1, justifyContent: 'center', marginLeft: 8, minHeight: 32, minWidth: 78, paddingHorizontal: 7},
  sessionLimitTerminateDisabled: {borderColor: uiColors.muted, opacity: 0.7},
  sessionLimitTerminateText: {color: uiColors.danger, fontFamily: UI_FONT_FAMILY, fontSize: 8, fontWeight: '800', letterSpacing: 0.1},
  sessionLimitActionError: {color: uiColors.danger, fontFamily: UI_FONT_FAMILY, fontSize: 9, lineHeight: 15, marginTop: 12, textAlign: 'center'},
});
