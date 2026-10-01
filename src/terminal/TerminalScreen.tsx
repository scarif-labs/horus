import React from 'react';
import {
  AppState,
  Keyboard,
  KeyboardAvoidingView,
  PermissionsAndroid,
  Platform,
  StyleSheet,
  StatusBar,
  Text,
  TextInput,
  View,
} from 'react-native';
import {UI_FONT_FAMILY} from '../ui/typography';

import type {Spec} from '../native/NativeTerminalRuntime';
import {NativeTerminalInput} from '../native/NativeTerminalInput';
import {
  TERMINAL_SESSION_DEFAULT_COLUMNS,
  TERMINAL_SESSION_DEFAULT_ROWS,
} from './session/sessionContract';
import {
  TerminalSessionClient,
  type TerminalSessionAttachment,
} from './session/sessionClient';
import {TerminalCellBuffer, terminalSize, TERMINAL_BACKGROUND, TERMINAL_FOREGROUND, type TerminalFrame, type TerminalSize} from './terminalBuffer';
import {TerminalGrid, TERMINAL_CELL_HEIGHT} from './TerminalGrid';
import {terminalMouseWheelSequence, type TerminalMouseWheel} from './terminalMouse';
import {
  installRootfs,
  readTerminalRuntimeStatus,
  type TerminalRuntimeStatusView,
} from './runtimeStatus';
import type {TerminalToolchainTarget} from '../native/NativeTerminalRuntime';
import {findGithubDeviceLoginUrl} from '../projects/githubDeviceLogin';
import type {ActiveTerminalSession, TrustedSessionOperation} from './session/sessionContract';
import {openTrustedTerminalLink} from './terminalLinks';
import {BrandMark} from './BrandMark';
import {formatSessionAge, sessionTitle, toolchainInstallLabel} from './toolchainLabels';
import {createRequestIdFactory} from './requestIds';
import {InteractivePressable as Pressable} from '../ui/InteractivePressable';

export type TerminalScreenProps = Readonly<{
  client?: TerminalSessionClient;
  runtime?: TerminalRuntimeBridge | null;
  onBack?: () => void;
  onHome?: () => void;
  onGithubDeviceLogin?: (url: string) => Promise<void>;
  screenEyebrow?: string;
  screenTitle?: string;
  sessionCommand?: string;
  /** Exact output line that marks a launcher command's successful handoff. */
  completionMarker?: string;
  onCompletion?: () => void;
  /** Called when a completion-marker command exits without its success marker. */
  onCommandFailure?: () => void;
  existingSessionId?: string;
  /** App bootstrap already verified the rootfs and packaged PRoot. */
  runtimeReady?: boolean;
  stopSessionOnUnmount?: boolean;
  toolchain?: TerminalToolchainTarget;
}>;

type TerminalViewState = 'idle' | 'starting' | 'running' | 'stopped' | 'error';

type TerminalRuntimeBridge = Pick<
  Spec,
  'getRuntimeStatus' | 'installRootfs' | 'resetRuntime'
>;

const nextRequestId = createRequestIdFactory('terminal');

const terminalChrome = {
  panel: '#0D1112',
  border: '#30383B',
  borderSoft: '#232A2D',
  muted: '#AAB7C6',
  accent: '#80EB12',
  danger: '#FF8795',
} as const;

const TERMINAL_LAYOUT_SETTLE_MS = 120;

type TerminalViewport = Readonly<{width: number; height: number}>;

type TerminalArrow = 'A' | 'B' | 'C' | 'D';

function controlCharacter(character: string): string {
  if (character === '?') return '\u007f';
  if (character === ' ' || character === '\t') return character === ' ' ? '\u0000' : '\t';
  const code = character.codePointAt(0);
  if (code === undefined || code > 0x7e) return character;
  if ((code >= 0x40 && code <= 0x5f) || (code >= 0x60 && code <= 0x7e)) {
    return String.fromCharCode(code & 0x1f);
  }
  return character;
}

function applyTerminalModifiers(value: string, ctrl: boolean, alt: boolean): string {
  return Array.from(value).map(character => {
    const modified = ctrl ? controlCharacter(character) : character;
    return alt ? `\u001b${modified}` : modified;
  }).join('');
}

function arrowSequence(direction: TerminalArrow, ctrl: boolean, alt: boolean): string {
  const modifier = (ctrl ? 4 : 0) + (alt ? 2 : 0);
  return modifier === 0 ? `\u001b[${direction}` : `\u001b[1;${modifier + 1}${direction}`;
}

function navigationSequence(parameter: string, final: string, ctrl: boolean, alt: boolean): string {
  const modifier = (ctrl ? 4 : 0) + (alt ? 2 : 0);
  return modifier === 0
    ? `\u001b[${parameter}${final}`
    : `\u001b[${parameter || 1};${modifier + 1}${final}`;
}

type TerminalToolbarButtonProps = Readonly<{
  active?: boolean;
  accessibilityLabel: string;
  disabled: boolean;
  label: string;
  onPress: () => void;
  testID: string;
}>;

function TerminalToolbarButton({active = false, accessibilityLabel, disabled, label, onPress, testID}: TerminalToolbarButtonProps): React.JSX.Element {
  return (
    <Pressable
      accessibilityLabel={accessibilityLabel}
      accessibilityRole="button"
      accessibilityState={{selected: active}}
      disabled={disabled}
      onPress={onPress}
      style={[styles.keyButton, active && styles.modifierButtonActive]}
      testID={testID}>
      <Text style={[styles.keyButtonText, active && styles.modifierButtonTextActive]}>{label}</Text>
    </Pressable>
  );
}

type TerminalReturnButtonProps = Readonly<{
  disabled: boolean;
  onPress: () => void;
}>;

function TerminalReturnUpperButton({disabled, onPress}: TerminalReturnButtonProps): React.JSX.Element {
  return (
    <Pressable
      accessibilityLabel="Return"
      accessibilityRole="button"
      disabled={disabled}
      onPress={onPress}
      style={styles.returnKeyUpper}
      testID="terminal-key-return-upper" />
  );
}

function TerminalReturnLowerButton({disabled, onPress}: TerminalReturnButtonProps): React.JSX.Element {
  return (
    <Pressable
      accessibilityLabel="Return"
      accessibilityRole="button"
      disabled={disabled}
      onPress={onPress}
      style={styles.returnKeyLower}
      testID="terminal-key-return">
      <View pointerEvents="none" style={styles.returnKeyNotch} testID="terminal-key-return-notch" />
      <Text style={styles.returnKeyGlyph}>↵</Text>
      <Text style={styles.returnKeyText}>RETURN</Text>
    </Pressable>
  );
}

function errorLabel(operation: TrustedSessionOperation | {kind: 'error'; errorCode: string} | {kind: 'incomplete'}): string {
  return operation.kind === 'error' ? operation.errorCode : operation.kind === 'incomplete' ? 'incomplete_stop' : 'unknown_error';
}

function terminalErrorMessage(errorCode: string): string {
  if (errorCode === 'session_limit_reached') {
    return 'Android closes apps that use too much memory, so Horus limits how many terminals and AI apps run at once, including ones left running in the background. Stop one below to start this one.';
  }
  return errorCode;
}

function toolchainInstallError(target: TerminalToolchainTarget): string {
  if (target === 'github') return 'toolchain_github_install_failed';
  if (target === 'claude') return 'toolchain_install_failed';
  if (target === 'codex') return 'toolchain_codex_install_failed';
  if (target === 'opencode') return 'toolchain_opencode_install_failed';
  return 'toolchain_install_failed';
}

function hasToolchainReadyMarker(value: string): boolean {
  return /(?:^|[\r\n])HORUS_TOOLCHAIN_READY(?:[\r\n]|$)/.test(value);
}

function hasExactOutputLine(value: string, expected: string): boolean {
  return value.split(/[\r\n]/).some(line => line === expected);
}

function hasVisibleTerminalContent(frame: TerminalFrame): boolean {
  return frame.lines.some(line => line.text.trim().length > 0);
}

type TerminalHeaderProps = Readonly<{
  screenEyebrow: string;
  screenTitle: string;
  onBack?: () => void;
  onHome?: () => void;
}>;

const TerminalHeader = React.memo(function TerminalHeaderView({onBack, onHome}: TerminalHeaderProps): React.JSX.Element {
  return (
    <View pointerEvents="box-none" style={styles.floatingHeader} testID="terminal-floating-header">
      {(onHome ?? onBack) !== undefined ? (
        <Pressable accessibilityLabel="Back to home" accessibilityRole="button" onPress={onHome ?? onBack} style={styles.floatingGroup} testID="terminal-home">
          <BrandMark accessible={false} size={28} />
          <Text style={styles.menuArrowText}>←</Text>
        </Pressable>
      ) : <View style={styles.floatingGroup}><BrandMark accessible={false} size={28} /><Text style={styles.menuArrowText}>←</Text></View>}
    </View>
  );
});

type TerminalControlsProps = Readonly<{
  altActive: boolean;
  ctrlActive: boolean;
  keyboardVisible: boolean;
  onArrow: (direction: TerminalArrow) => void;
  onEscape: () => void;
  onKeyboardToggle: () => void;
  onNavigation: (parameter: string, final: string) => void;
  onReturn: () => void;
  onTerminalKey: (value: string) => void;
  onToggleAlt: () => void;
  onToggleCtrl: () => void;
  onToggleTranscriptPager: () => void;
  running: boolean;
  toolchain: TerminalToolchainTarget;
  transcriptPagerOpen: boolean;
}>;

const TerminalControls = React.memo(function TerminalControlsView({altActive, ctrlActive, keyboardVisible, onArrow, onEscape, onKeyboardToggle, onNavigation, onReturn, onTerminalKey, onToggleAlt, onToggleCtrl, onToggleTranscriptPager, running, toolchain, transcriptPagerOpen}: TerminalControlsProps): React.JSX.Element {
  return (
    <View style={styles.controls}>
      <View style={styles.controlRows}>
        <View style={styles.controlRow}>
          <View style={styles.controlRowMain}>
            <TerminalToolbarButton accessibilityLabel="Escape" disabled={!running} label="ESC" onPress={onEscape} testID="terminal-key-esc" />
            <TerminalToolbarButton accessibilityLabel="Slash" disabled={!running} label="/" onPress={() => onTerminalKey('/')} testID="terminal-key-slash" />
            <TerminalToolbarButton accessibilityLabel="Dash" disabled={!running} label="―" onPress={() => onTerminalKey('-')} testID="terminal-key-dash" />
            <TerminalToolbarButton accessibilityLabel={keyboardVisible ? 'Hide keyboard' : 'Show keyboard'} disabled={!running} label={keyboardVisible ? 'HIDE' : 'SHOW'} onPress={onKeyboardToggle} testID="terminal-keyboard-toggle" />
            <TerminalToolbarButton accessibilityLabel="Arrow up" disabled={!running} label="↑" onPress={() => onArrow('A')} testID="terminal-key-arrow-up" />
            <TerminalToolbarButton accessibilityLabel="End" disabled={!running} label="END" onPress={() => onNavigation('', 'F')} testID="terminal-key-end" />
            {toolchain === 'codex' && running ? (
              <TerminalToolbarButton
                accessibilityLabel={transcriptPagerOpen ? 'Close transcript history' : 'Open transcript history'}
                disabled={!running}
                label={transcriptPagerOpen ? 'CHAT' : 'HIST'}
                onPress={onToggleTranscriptPager}
                testID="terminal-key-transcript-history"
              />
            ) : null}
          </View>
          <TerminalReturnUpperButton disabled={!running} onPress={onReturn} />
        </View>
        <View style={styles.controlRow}>
          <View style={styles.controlRowMain}>
            <TerminalToolbarButton accessibilityLabel="Tab" disabled={!running} label="TAB" onPress={() => onTerminalKey('\t')} testID="terminal-key-tab" />
            <TerminalToolbarButton accessibilityLabel="Control modifier" active={ctrlActive} disabled={!running} label="CTRL" onPress={onToggleCtrl} testID="terminal-key-ctrl" />
            <TerminalToolbarButton accessibilityLabel="Alt modifier" active={altActive} disabled={!running} label="ALT" onPress={onToggleAlt} testID="terminal-key-alt" />
            <TerminalToolbarButton accessibilityLabel="Arrow left" disabled={!running} label="←" onPress={() => onArrow('D')} testID="terminal-key-arrow-left" />
            <TerminalToolbarButton accessibilityLabel="Arrow down" disabled={!running} label="↓" onPress={() => onArrow('B')} testID="terminal-key-arrow-down" />
            <TerminalToolbarButton accessibilityLabel="Arrow right" disabled={!running} label="→" onPress={() => onArrow('C')} testID="terminal-key-arrow-right" />
          </View>
          <TerminalReturnLowerButton disabled={!running} onPress={onReturn} />
        </View>
      </View>
    </View>
  );
});

export function TerminalScreen({client: providedClient, runtime = undefined, onBack, onGithubDeviceLogin, onHome, screenEyebrow = 'ALPINE TERMINAL', screenTitle = 'Linux shell', sessionCommand, completionMarker, onCompletion, onCommandFailure, existingSessionId, runtimeReady = false, stopSessionOnUnmount = true, toolchain = 'shell'}: TerminalScreenProps): React.JSX.Element {
  const [viewport, setViewport] = React.useState({width: 0, height: 0});
  const viewportRef = React.useRef(viewport);
  const pendingViewportRef = React.useRef<TerminalViewport | undefined>(undefined);
  const viewportTimerRef = React.useRef<ReturnType<typeof setTimeout> | undefined>(undefined);
  const [cellWidth, setCellWidth] = React.useState(8);
  const [keyboardVisible, setKeyboardVisible] = React.useState(Keyboard.isVisible());
  const keyboardVisibleRef = React.useRef(keyboardVisible);
  const lastExpandedViewportRef = React.useRef<TerminalViewport | undefined>(undefined);
  const [transcriptPagerOpen, setTranscriptPagerOpen] = React.useState(false);
  const size = terminalSize(
    viewport.height > 0 ? viewport.height / TERMINAL_CELL_HEIGHT : TERMINAL_SESSION_DEFAULT_ROWS,
    viewport.width > 0 ? viewport.width / cellWidth : TERMINAL_SESSION_DEFAULT_COLUMNS,
  );
  const sizeRef = React.useRef(size);
  sizeRef.current = size;
  const requestedSizeRef = React.useRef<TerminalSize | undefined>(undefined);
  const client = React.useMemo(
    () => providedClient ?? new TerminalSessionClient(),
    [providedClient],
  );
  const [state, setState] = React.useState<TerminalViewState>('idle');
  const [frame, setFrame] = React.useState<TerminalFrame | undefined>(undefined);
  const [nativeSessionId, setNativeSessionId] = React.useState<string | undefined>(undefined);
  const [terminalInputSessionId, setTerminalInputSessionId] = React.useState<string | undefined>(undefined);
  const [nativeHarnessReady, setNativeHarnessReady] = React.useState(false);
  const [keyboardShowRequest, setKeyboardShowRequest] = React.useState(0);
  const [keyboardHideRequest, setKeyboardHideRequest] = React.useState(0);
  const [harnessFrameReady, setHarnessFrameReady] = React.useState(false);
  const displayBufferRef = React.useRef<TerminalCellBuffer | undefined>(undefined);
  const [error, setError] = React.useState<string | undefined>(undefined);
  const sessionLimitReached = error === 'session_limit_reached';
  const [limitSessions, setLimitSessions] = React.useState<readonly ActiveTerminalSession[]>([]);
  const [limitSessionsLoading, setLimitSessionsLoading] = React.useState(false);
  const [terminatingLimitSessionId, setTerminatingLimitSessionId] = React.useState<string | undefined>();
  const [limitSessionActionError, setLimitSessionActionError] = React.useState(false);
  const [outputHistoryGap, setOutputHistoryGap] = React.useState(false);
  const [ctrlActive, setCtrlActive] = React.useState(false);
  const [altActive, setAltActive] = React.useState(false);
  const activeSessionRef = React.useRef<string | undefined>(undefined);
  const attachmentRef = React.useRef<TerminalSessionAttachment | undefined>(undefined);
  const lastOutputSeqRef = React.useRef(0);
  const isAttachedRef = React.useRef(false);
  const appStateRef = React.useRef(AppState.currentState);
  const terminalInputRef = React.useRef<React.ElementRef<typeof TextInput>>(null);
  const nativeTerminalInputRef = React.useRef<React.ElementRef<typeof NativeTerminalInput>>(null);
  const mountedRef = React.useRef(true);
  const startingRef = React.useRef(false);
  const inputLayoutReadyRef = React.useRef(false);
  const keyboardInputValueRef = React.useRef('');
  const keyboardFocusFrameRef = React.useRef<number | null>(null);
  const githubLoginOutputTailRef = React.useRef('');
  const openedGithubLoginUrlsRef = React.useRef<Set<string>>(new Set());
  const installOutputTailRef = React.useRef('');
  const installReadyRef = React.useRef(false);
  const completionOutputTailRef = React.useRef('');
  const completionMarkerSeenRef = React.useRef(false);
  const completionDeliveredRef = React.useRef(false);
  // Interactive sessions parse and draw natively. One-off shell commands that
  // wait for a completion marker, and the GitHub login flow, keep the JS path
  // because they scan output in JS.
  const nativeHarness = Platform.OS === 'android' && !Platform.isTesting &&
    (toolchain === 'claude' || toolchain === 'codex' || toolchain === 'opencode' ||
      (toolchain === 'shell' && completionMarker === undefined));
  const nativeTerminalInput = Platform.OS === 'android' && !Platform.isTesting;
  const nativeOpenCode = nativeHarness && toolchain === 'opencode';

  viewportRef.current = viewport;

  const commitViewport = React.useCallback((nextViewport: TerminalViewport) => {
    const expanded = lastExpandedViewportRef.current;
    // Keep the largest hidden-keyboard viewport. Android can dispatch the
    // shrinking layout before keyboardDidShow, so a low intermediate height
    // must not replace the geometry we need to restore on keyboardDidHide.
    if (!keyboardVisibleRef.current &&
      (expanded === undefined || nextViewport.height >= expanded.height || nextViewport.width !== expanded.width)) {
      lastExpandedViewportRef.current = nextViewport;
    }
    const previous = viewportRef.current;
    if (previous.width === nextViewport.width && previous.height === nextViewport.height) return;
    viewportRef.current = nextViewport;
    setViewport(nextViewport);
  }, []);

  const scheduleViewportCommit = React.useCallback((nextViewport: TerminalViewport) => {
    pendingViewportRef.current = nextViewport;
    if (viewportRef.current.width === 0 || viewportRef.current.height === 0) {
      pendingViewportRef.current = undefined;
      commitViewport(nextViewport);
      return;
    }
    if (viewportTimerRef.current !== undefined) clearTimeout(viewportTimerRef.current);
    viewportTimerRef.current = setTimeout(() => {
      viewportTimerRef.current = undefined;
      const pending = pendingViewportRef.current;
      pendingViewportRef.current = undefined;
      if (pending !== undefined) commitViewport(pending);
    }, TERMINAL_LAYOUT_SETTLE_MS);
  }, [commitViewport]);

  const handleOutputLayout = React.useCallback((event: {nativeEvent: {layout: {width: number; height: number}}}) => {
    const {width, height} = event.nativeEvent.layout;
    if (!Number.isFinite(width) || !Number.isFinite(height) || width <= 0 || height <= 0) return;
    scheduleViewportCommit({width, height});
  }, [scheduleViewportCommit]);

  const deliverCompletion = React.useCallback(() => {
    if (!completionMarkerSeenRef.current || completionDeliveredRef.current) return;
    completionDeliveredRef.current = true;
    onCompletion?.();
  }, [onCompletion]);

  const deliverCommandFailure = React.useCallback(() => {
    if (completionMarker === undefined || completionMarkerSeenRef.current || completionDeliveredRef.current) return;
    completionDeliveredRef.current = true;
    onCommandFailure?.();
  }, [completionMarker, onCommandFailure]);

  const disposeAttachment = React.useCallback(() => {
    isAttachedRef.current = false;
    client.dispose();
  }, [client]);

  const openTerminalLink = React.useCallback((url: string) => {
    void openTrustedTerminalLink(url).catch(() => {
      if (mountedRef.current) setError('link_unavailable');
    });
  }, []);

  React.useEffect(() => {
    mountedRef.current = true;
    return () => {
      mountedRef.current = false;
      if (keyboardFocusFrameRef.current !== null) {
        cancelAnimationFrame(keyboardFocusFrameRef.current);
        keyboardFocusFrameRef.current = null;
      }
      if (viewportTimerRef.current !== undefined) {
        clearTimeout(viewportTimerRef.current);
        viewportTimerRef.current = undefined;
      }
      pendingViewportRef.current = undefined;
      displayBufferRef.current?.dispose();
      displayBufferRef.current = undefined;
      setNativeSessionId(undefined);
      setTerminalInputSessionId(undefined);
      setNativeHarnessReady(false);
      disposeAttachment();
      const activeSession = activeSessionRef.current;
      activeSessionRef.current = undefined;
      attachmentRef.current = undefined;
      lastOutputSeqRef.current = 0;
      if (activeSession !== undefined && stopSessionOnUnmount) {
        void client.stopSession(nextRequestId('unmount'), activeSession, 'screen_unmount');
      }
      client.dispose();
    };
  }, [client, disposeAttachment, stopSessionOnUnmount]);

  React.useEffect(() => {
    if (Platform.OS !== 'android' || Number(Platform.Version) < 33) return;
    void PermissionsAndroid.request(PermissionsAndroid.PERMISSIONS.POST_NOTIFICATIONS).catch(() => undefined);
  }, []);

  React.useEffect(() => {
    const showSubscription = Keyboard.addListener('keyboardDidShow', () => {
      keyboardVisibleRef.current = true;
      setKeyboardVisible(true);
    });
    const hideSubscription = Keyboard.addListener('keyboardDidHide', () => {
      keyboardVisibleRef.current = false;
      setKeyboardVisible(false);
      const expanded = lastExpandedViewportRef.current;
      if (expanded !== undefined) scheduleViewportCommit(expanded);
    });
    return () => {
      showSubscription.remove();
      hideSubscription.remove();
    };
  }, [scheduleViewportCommit]);

  React.useEffect(() => {
    const subscription = AppState.addEventListener('change', nextState => {
      const previousState = appStateRef.current;
      appStateRef.current = nextState;
      const sessionId = activeSessionRef.current;
      if (nextState === 'background' || nextState === 'inactive') {
        if (sessionId !== undefined && isAttachedRef.current) disposeAttachment();
        return;
      }
      if (previousState === 'active' || sessionId === undefined || isAttachedRef.current) return;
      const attachment = attachmentRef.current;
      if (attachment === undefined) return;
      isAttachedRef.current = true;
      void client.attachAndSubscribe(
        nextRequestId('foreground-subscribe'),
        sessionId,
        attachment,
        lastOutputSeqRef.current,
      ).then(outcome => {
        if (
          !mountedRef.current ||
          activeSessionRef.current !== sessionId ||
          !isAttachedRef.current ||
          appStateRef.current !== 'active'
        ) return;
        if (outcome.kind !== 'success') {
          isAttachedRef.current = false;
          setError(errorLabel(outcome));
          return;
        }
        if (outcome.sessionState === 'exited') {
          isAttachedRef.current = false;
          activeSessionRef.current = undefined;
          attachmentRef.current = undefined;
          setState('stopped');
          setError(outcome.signal === undefined ? undefined : `exited:${outcome.signal}`);
          // The exit happened while backgrounded, so onExit never ran.
          deliverCompletion();
          deliverCommandFailure();
          return;
        }
        setError(undefined);
        setState('running');
      }).catch(() => {
        if (mountedRef.current && activeSessionRef.current === sessionId) {
          isAttachedRef.current = false;
          setError('subscription_failed');
        }
      });
    });
    return () => subscription.remove();
  }, [client, deliverCommandFailure, deliverCompletion, disposeAttachment]);

  const readRuntimeStatus = React.useCallback(async (): Promise<TerminalRuntimeStatusView> => {
    if (runtime === undefined) return readTerminalRuntimeStatus();
    return readTerminalRuntimeStatus(runtime as Spec);
  }, [runtime]);

  const refreshRuntimeStatus = React.useCallback(async (): Promise<TerminalRuntimeStatusView | undefined> => {
    const view = await readRuntimeStatus();
    if (!mountedRef.current) return undefined;
    return view;
  }, [readRuntimeStatus]);

  const setRuntimeFailure = React.useCallback((errorCode: string) => {
    if (!mountedRef.current) return;
    setError(errorCode);
    setState('error');
  }, []);

  const startPtySession = React.useCallback(async (sessionToResume?: string) => {
    let sessionId: string;
    if (sessionToResume !== undefined) {
      sessionId = sessionToResume;
      requestedSizeRef.current = sizeRef.current;
    } else {
      const started = await client.startSession(nextRequestId('start'), {
        ...sizeRef.current,
        command: sessionCommand,
        toolchain,
        // Clone, login, and logout terminals are short helper commands that
        // end on their own; they must not take the slot of the app they open.
        ...(completionMarker === undefined ? {} : {countsAgainstSessionLimit: false}),
      });
      if (!mountedRef.current) {
        // App and shell sessions outlive their screen, and the service hands
        // the same running session to the next screen that starts this
        // command. Stopping it here would kill the remounted screen's session.
        if (started.kind === 'success' && stopSessionOnUnmount) {
          await client.stopSession(nextRequestId('late-stop'), started.sessionId, 'screen_unmount');
        }
        return;
      }
      if (started.kind !== 'success') {
        setState('error');
        setError(started.errorCode);
        deliverCommandFailure();
        return;
      }
      sessionId = started.sessionId;
      requestedSizeRef.current = {rows: started.rows, columns: started.columns};
    }
    if (!mountedRef.current) return;
    lastOutputSeqRef.current = 0;
    activeSessionRef.current = sessionId;
    setTerminalInputSessionId(nativeTerminalInput ? sessionId : undefined);
    setNativeSessionId(nativeHarness ? sessionId : undefined);
    setNativeHarnessReady(nativeHarness && sessionToResume !== undefined);
    displayBufferRef.current = nativeHarness ? undefined : new TerminalCellBuffer(requestedSizeRef.current, {
      onChange: nextFrame => {
        if (!mountedRef.current) return;
        if (toolchain !== 'shell' && installReadyRef.current && nextFrame.alternate && hasVisibleTerminalContent(nextFrame)) {
          setHarnessFrameReady(true);
        }
        setFrame(nextFrame);
      },
      onReply: async data => {
        if (!mountedRef.current || activeSessionRef.current !== sessionId) return;
        const outcome = await client.writeSessionInput(
          nextRequestId('terminal-reply'), sessionId, new TextEncoder().encode(data),
        );
        if (outcome.kind === 'error') throw new Error('terminal_reply_failed');
      },
      onError: code => {
        if (!mountedRef.current) return;
        setState('error');
        setError(code);
        disposeAttachment();
        const activeSession = activeSessionRef.current;
        activeSessionRef.current = undefined;
        if (activeSession !== undefined && sessionToResume === undefined) {
          void client.stopSession(nextRequestId('renderer-stop'), activeSession, 'screen_unmount');
        }
      },
    });
    if (sessionToResume !== undefined && displayBufferRef.current !== undefined) setFrame(displayBufferRef.current.snapshot());
    const attachment: TerminalSessionAttachment = {
      onOutput: chunk => {
        if (!mountedRef.current) return;
        lastOutputSeqRef.current = Math.max(lastOutputSeqRef.current, chunk.seq);
        const scanInstallMarker = !installReadyRef.current;
        const scanGithubLogin = toolchain === 'github' && onGithubDeviceLogin !== undefined;
        const scanCompletionMarker = completionMarker !== undefined && !completionMarkerSeenRef.current;
        if (scanInstallMarker || scanGithubLogin || scanCompletionMarker) {
          const outputText = new TextDecoder().decode(chunk.bytes);
          if (scanInstallMarker) {
            const installScanText = `${installOutputTailRef.current}${outputText}`;
            installOutputTailRef.current = installScanText.slice(-512);
            if (hasToolchainReadyMarker(installScanText)) {
              installReadyRef.current = true;
              if (nativeHarness) setNativeHarnessReady(true);
            }
          }
          if (scanGithubLogin) {
            const scanText = `${githubLoginOutputTailRef.current}${outputText}`;
            githubLoginOutputTailRef.current = scanText.slice(-512);
            const githubLoginUrl = findGithubDeviceLoginUrl(scanText);
            if (
              githubLoginUrl !== undefined &&
              !openedGithubLoginUrlsRef.current.has(githubLoginUrl)
            ) {
              openedGithubLoginUrlsRef.current.add(githubLoginUrl);
              void (async () => {
                await onGithubDeviceLogin(githubLoginUrl);
                if (!mountedRef.current || activeSessionRef.current !== sessionId) return;
                const confirmed = await client.writeSessionInput(
                  nextRequestId('github-browser-confirm'),
                  sessionId,
                  new TextEncoder().encode('\r'),
                );
                if (confirmed.kind === 'error') throw new Error('github_browser_confirm_failed');
              })().catch(() => {
                if (mountedRef.current && activeSessionRef.current === sessionId) setError('github_browser_unavailable');
              });
            }
          }
          if (scanCompletionMarker) {
            const scanText = `${completionOutputTailRef.current}${outputText}`;
            completionOutputTailRef.current = scanText.slice(-(completionMarker.length + 2));
            if (hasExactOutputLine(scanText, completionMarker)) completionMarkerSeenRef.current = true;
          }
        }
        displayBufferRef.current?.append(chunk.bytes);
      },
      onExit: exit => {
        client.dispose();
        isAttachedRef.current = false;
        attachmentRef.current = undefined;
        setNativeSessionId(undefined);
        setTerminalInputSessionId(undefined);
        setNativeHarnessReady(false);
        if (!mountedRef.current) return;
        activeSessionRef.current = undefined;
        if (!installReadyRef.current) {
          setState('error');
          setError(toolchainInstallError(toolchain));
          deliverCommandFailure();
        } else {
          setState('stopped');
          setError(exit.signal === undefined ? undefined : `exited:${exit.signal}`);
          deliverCompletion();
          deliverCommandFailure();
        }
      },
      onProtocolError: protocolError => {
        if (!mountedRef.current) return;
        if (protocolError.kind === 'output_gap' || protocolError.kind === 'sequence_gap') {
          // Native sessions stop sending output to JS once ready, so this
          // cursor is stale for them; the native engine detects real gaps and
          // makes the app repaint instead.
          if (!nativeHarness) setOutputHistoryGap(true);
          return;
        }
        setState('error');
        setError(`${protocolError.kind}:${protocolError.detail}`.slice(0, 160));
      },
    };
    attachmentRef.current = attachment;
    isAttachedRef.current = true;
    try {
      const subscription = await client.attachAndSubscribe(
        nextRequestId('subscribe'),
        sessionId,
        attachment,
      );
      if (subscription.kind !== 'success') {
        disposeAttachment();
        if (sessionToResume === undefined) {
          await client.stopSession(nextRequestId('subscribe-failed-stop'), sessionId, 'screen_unmount');
        }
        activeSessionRef.current = undefined;
        setState('error');
        setError(errorLabel(subscription));
        deliverCommandFailure();
        return;
      }
      if (subscription.sessionState === 'exited') {
        disposeAttachment();
        isAttachedRef.current = false;
        activeSessionRef.current = undefined;
        attachmentRef.current = undefined;
        setState('stopped');
        setError(subscription.signal === undefined ? undefined : `exited:${subscription.signal}`);
        deliverCompletion();
        deliverCommandFailure();
        return;
      }
      if (mountedRef.current && activeSessionRef.current === sessionId) {
        setState('running');
        if (appStateRef.current === 'background' || appStateRef.current === 'inactive') {
          disposeAttachment();
        }
      }
    } catch {
      disposeAttachment();
      if (sessionToResume === undefined) {
        await client.stopSession(nextRequestId('subscribe-error-stop'), sessionId, 'screen_unmount');
      }
      activeSessionRef.current = undefined;
      attachmentRef.current = undefined;
      setState('error');
      setError('subscription_failed');
      deliverCommandFailure();
    }
  }, [client, completionMarker, deliverCommandFailure, deliverCompletion, disposeAttachment, nativeHarness, nativeTerminalInput, onGithubDeviceLogin, sessionCommand, stopSessionOnUnmount, toolchain]);

  const start = React.useCallback(async () => {
    if (activeSessionRef.current !== undefined || startingRef.current) return;
    startingRef.current = true;
    setState('starting');
    setError(undefined);
    setOutputHistoryGap(false);
    githubLoginOutputTailRef.current = '';
    openedGithubLoginUrlsRef.current.clear();
    installOutputTailRef.current = '';
    completionOutputTailRef.current = '';
    completionMarkerSeenRef.current = false;
    completionDeliveredRef.current = false;
    installReadyRef.current = false;
    setHarnessFrameReady(false);
    displayBufferRef.current?.dispose();
    displayBufferRef.current = undefined;
    setNativeSessionId(undefined);
    setTerminalInputSessionId(undefined);
    setNativeHarnessReady(false);
    setFrame(undefined);
    try {
      if (existingSessionId !== undefined) {
        installReadyRef.current = true;
        await startPtySession(existingSessionId);
        return;
      }
      if (runtimeReady) {
        await startPtySession();
        return;
      }
      const status = await refreshRuntimeStatus();
      if (status === undefined || !mountedRef.current) return;
      if (status.kind === 'error') {
        setRuntimeFailure(status.errorCode);
        return;
      }
      if (status.runtimeState === 'not_installed') {
        const installed = runtime === undefined
          ? await installRootfs(nextRequestId('install'))
          : await installRootfs(nextRequestId('install'), runtime);
        if (!mountedRef.current) return;
        if (installed.kind !== 'success') {
          setRuntimeFailure(installed.errorCode);
          return;
        }
        const verified = await refreshRuntimeStatus();
        if (verified === undefined || !mountedRef.current) return;
        if (verified.kind === 'error') {
          setRuntimeFailure(verified.errorCode);
          return;
        }
        if (verified.runtimeState !== 'ready') {
          setRuntimeFailure('runtime_not_ready');
          return;
        }
        if (!verified.prootAvailable) {
          setRuntimeFailure('proot_unavailable');
          return;
        }
      } else if (!status.prootAvailable) {
        setRuntimeFailure('proot_unavailable');
        return;
      }
      await startPtySession();
    } finally {
      startingRef.current = false;
    }
  }, [existingSessionId, refreshRuntimeStatus, runtime, runtimeReady, setRuntimeFailure, startPtySession]);

  React.useEffect(() => {
    void start();
  }, [start]);

  React.useEffect(() => {
    if (!sessionLimitReached) {
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
  }, [client, sessionLimitReached]);

  React.useEffect(() => {
    if (state !== 'running' || !inputLayoutReadyRef.current) return;
    if (nativeTerminalInput) nativeTerminalInputRef.current?.focus();
    else terminalInputRef.current?.focus();
  }, [nativeTerminalInput, state]);

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
    await start();
  }, [client, start, terminatingLimitSessionId]);

  const write = React.useCallback(async (text: string) => {
    const activeSession = activeSessionRef.current;
    if (activeSession === undefined || text.length === 0) return;
    const bytes = new TextEncoder().encode(text);
    const outcome = await client.writeSessionInput(nextRequestId('input'), activeSession, bytes);
    if (outcome.kind === 'error' && mountedRef.current) {
      setState('error');
      setError(outcome.errorCode);
    }
  }, [client]);

  const clearKeyboardInput = React.useCallback(() => {
    keyboardInputValueRef.current = '';
    terminalInputRef.current?.clear();
  }, []);

  const handleTerminalText = React.useCallback((text: string) => {
    const previous = keyboardInputValueRef.current;
    if (text === previous) return;
    keyboardInputValueRef.current = text;
    // Use committed text for typing/paste/hardware input. Android onKeyPress
    // is an IME heuristic and can omit characters from a batched commit.
    const before = Array.from(previous);
    const after = Array.from(text);
    let common = 0;
    while (common < before.length && common < after.length && before[common] === after[common]) common += 1;
    void write(
      '\u007f'.repeat(before.length - common) +
      applyTerminalModifiers(after.slice(common).join(''), ctrlActive, altActive),
    );
  }, [altActive, ctrlActive, write]);

  const handleTerminalKeyPress = React.useCallback((event: {nativeEvent: {key: string}}) => {
    // RN emits soft-key Backspace before the corresponding text change.
    // Only an already empty input needs this fallback (there is no change).
    if (event.nativeEvent.key === 'Backspace' && keyboardInputValueRef.current.length === 0) {
      void write('\u007f');
    }
  }, [write]);

  const running = state === 'running';
  const focusTerminalInput = React.useCallback(() => {
    if (!running) return;
    const input = nativeTerminalInput ? nativeTerminalInputRef.current : terminalInputRef.current;
    if (input === null) return;
    if (nativeTerminalInput) {
      input.focus();
      if (!keyboardVisible) {
        setKeyboardShowRequest(value => value >= Number.MAX_SAFE_INTEGER ? 1 : value + 1);
      }
      return;
    }
    if (Keyboard.isVisible()) {
      input.focus();
      return;
    }
    // Android can leave the editor focused after Back hides the IME. A plain
    // focus() is then a no-op, so reset focus and request it on the next frame.
    input.blur();
    if (keyboardFocusFrameRef.current !== null) cancelAnimationFrame(keyboardFocusFrameRef.current);
    keyboardFocusFrameRef.current = requestAnimationFrame(() => {
      keyboardFocusFrameRef.current = null;
      if (mountedRef.current && activeSessionRef.current !== undefined) {
        if (nativeTerminalInput) nativeTerminalInputRef.current?.focus();
        else terminalInputRef.current?.focus();
      }
    });
  }, [keyboardVisible, nativeTerminalInput, running]);

  const sendTerminalKey = React.useCallback((value: string) => {
    void write(applyTerminalModifiers(value, ctrlActive, altActive));
  }, [altActive, ctrlActive, write]);

  const sendEscape = React.useCallback(() => {
    if (toolchain === 'codex' && transcriptPagerOpen) {
      setTranscriptPagerOpen(false);
      void write('\u001b');
      return;
    }
    sendTerminalKey('\u001b');
  }, [sendTerminalKey, toolchain, transcriptPagerOpen, write]);

  const sendArrow = React.useCallback((direction: TerminalArrow) => {
    void write(arrowSequence(direction, ctrlActive, altActive));
  }, [altActive, ctrlActive, write]);

  const sendNavigation = React.useCallback((parameter: string, final: string) => {
    void write(navigationSequence(parameter, final, ctrlActive, altActive));
  }, [altActive, ctrlActive, write]);

  const sendPageKey = React.useCallback((direction: 'up' | 'down') => {
    const sequence = direction === 'up' ? '\u001b[5~' : '\u001b[6~';
    if (toolchain === 'codex' && direction === 'up' && !transcriptPagerOpen) {
      setTranscriptPagerOpen(true);
      void (async () => {
        await write('\u0014');
        await write(sequence);
      })();
      return;
    }
    void write(sequence);
  }, [toolchain, transcriptPagerOpen, write]);

  const toggleCodexTranscriptPager = React.useCallback(() => {
    setTranscriptPagerOpen(open => !open);
    void write('\u0014');
  }, [write]);

  const handleOutputSwipe = React.useCallback((direction: 'up' | 'down') => {
    sendPageKey(direction === 'down' ? 'up' : 'down');
  }, [sendPageKey]);

  const handleOutputMouseWheel = React.useCallback((event: TerminalMouseWheel) => {
    void write(terminalMouseWheelSequence(event));
  }, [write]);

  const handleOutputScrollStateChange = React.useCallback((scrolling: boolean) => {
    displayBufferRef.current?.setScrolling(scrolling);
  }, []);

  const hideTerminalKeyboard = React.useCallback(() => {
    if (nativeTerminalInput) {
      setKeyboardHideRequest(value => value >= Number.MAX_SAFE_INTEGER ? 1 : value + 1);
    } else {
      Keyboard.dismiss();
      terminalInputRef.current?.blur();
    }
    setKeyboardVisible(false);
  }, [nativeTerminalInput]);

  const toggleTerminalKeyboard = React.useCallback(() => {
    if (keyboardVisible) {
      hideTerminalKeyboard();
      return;
    }
    focusTerminalInput();
  }, [focusTerminalInput, hideTerminalKeyboard, keyboardVisible]);

  const submitTerminalInput = React.useCallback(() => {
    clearKeyboardInput();
    void write(applyTerminalModifiers('\r', ctrlActive, altActive));
  }, [altActive, clearKeyboardInput, ctrlActive, write]);
  const toggleCtrl = React.useCallback(() => setCtrlActive(value => !value), []);
  const toggleAlt = React.useCallback(() => setAltActive(value => !value), []);

  React.useEffect(() => {
    const activeSession = activeSessionRef.current;
    if (activeSession === undefined || state !== 'running') return;
    const requested = requestedSizeRef.current;
    if (requested?.rows === size.rows && requested.columns === size.columns) return;
    const expandedViewport = lastExpandedViewportRef.current;
    const expandedRows = expandedViewport === undefined
      ? undefined
      : terminalSize(expandedViewport.height / TERMINAL_CELL_HEIGHT, size.columns).rows;
    // The Android IME changes the React viewport height, but it should not
    // resize the PTY/native parser. A SIGWINCH here makes Codex/Claude redraw
    // their whole screen and causes the visible prompt to jump to the top.
    // A shell has no full-screen redraw to protect: resize it like a desktop
    // terminal so the prompt stays above the keyboard.
    if (nativeHarness && toolchain !== 'shell' && expandedRows !== undefined && size.columns === requested?.columns && size.rows < expandedRows) return;
    const nextSize = {rows: size.rows, columns: size.columns};
    requestedSizeRef.current = nextSize;
    if (nativeHarness) {
      client.resizeSession(nextRequestId('resize'), activeSession, nextSize.rows, nextSize.columns).then(result => {
        if (result.kind === 'error' && mountedRef.current && activeSessionRef.current === activeSession) {
          setState('error');
          setError(result.errorCode);
        }
      });
      return;
    }
    displayBufferRef.current?.resize(nextSize, async () => {
      if (!mountedRef.current || activeSessionRef.current !== activeSession) return;
      const result = await client.resizeSession(nextRequestId('resize'), activeSession, nextSize.rows, nextSize.columns);
      if (result.kind === 'error') throw new Error('terminal_resize_failed');
    });
  }, [client, nativeHarness, size.rows, size.columns, state, toolchain]);

  return (
    <>
      <StatusBar barStyle="light-content" />
      <View style={styles.safeArea} testID="terminal-screen">
        <KeyboardAvoidingView behavior={Platform.OS === 'ios' ? 'padding' : 'height'} keyboardVerticalOffset={0} style={styles.keyboardRoot}>
          <TerminalHeader onBack={onBack} onHome={onHome} screenEyebrow={screenEyebrow} screenTitle={screenTitle} />

          <View onLayout={handleOutputLayout} style={styles.output}>
            <TerminalGrid
              frame={frame}
              nativeSessionId={nativeSessionId}
              nativeRows={nativeSessionId !== undefined ? requestedSizeRef.current?.rows ?? size.rows : requestedSizeRef.current?.rows ?? size.rows}
              nativeColumns={nativeSessionId !== undefined ? size.columns : requestedSizeRef.current?.columns ?? size.columns}
              cellWidth={cellWidth}
              running={running}
              startupOverlay={(state === 'starting' || state === 'running') && toolchain !== 'shell' &&
                (nativeOpenCode || (nativeSessionId !== undefined && !nativeHarnessReady) || (installReadyRef.current && !harnessFrameReady && frame?.alternate === true))
                ? `Starting ${toolchainInstallLabel(toolchain)}…`
                : undefined}
              onCellWidth={setCellWidth}
              onTap={focusTerminalInput}
              onLinkPress={openTerminalLink}
              onSwipe={handleOutputSwipe}
              onMouseWheel={handleOutputMouseWheel}
              onScrollStateChange={handleOutputScrollStateChange}
              placeholder={`${toolchainInstallLabel(toolchain)} install starting…`}
              onLayout={handleOutputLayout}
            />
            {sessionLimitReached ? (
              <View pointerEvents="auto" style={styles.sessionLimitOverlay} testID="session-limit-warning-overlay">
                <View style={styles.sessionLimitCard} testID="session-limit-warning">
                  <Text style={styles.sessionLimitTitle} testID="session-limit-warning-title">Too many apps running</Text>
                  <Text style={styles.sessionLimitMessage} testID="session-limit-warning-message">
                    {terminalErrorMessage(error)}
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
            ) : null}
          </View>

          {nativeTerminalInput ? (
            <NativeTerminalInput
              ref={nativeTerminalInputRef}
              sessionId={terminalInputSessionId}
              terminalEnabled={running}
              terminalAutoFocus
              ctrlActive={ctrlActive}
              altActive={altActive}
              keyboardShowRequest={keyboardShowRequest}
              keyboardHideRequest={keyboardHideRequest}
              onLayout={() => {
                const wasLayoutReady = inputLayoutReadyRef.current;
                inputLayoutReadyRef.current = true;
                if (running && !wasLayoutReady) nativeTerminalInputRef.current?.focus();
              }}
              pointerEvents="none"
              style={styles.keyboardInput}
              testID="terminal-input"
            />
          ) : (
            <TextInput
              ref={terminalInputRef}
              autoCapitalize="none"
              autoComplete="off"
              autoCorrect={false}
              autoFocus
              editable={running}
              importantForAutofill="noExcludeDescendants"
              onChangeText={handleTerminalText}
              onKeyPress={handleTerminalKeyPress}
              onLayout={() => {
                const wasLayoutReady = inputLayoutReadyRef.current;
                inputLayoutReadyRef.current = true;
                if (running && !wasLayoutReady) terminalInputRef.current?.focus();
              }}
              onSubmitEditing={submitTerminalInput}
              submitBehavior="submit"
              returnKeyType="send"
              pointerEvents="none"
              selectionColor="transparent"
              showSoftInputOnFocus
              style={styles.keyboardInput}
              maxLength={4096}
              testID="terminal-input"
            />
          )}

          {outputHistoryGap ? (
            <Text style={styles.historyNotice} testID="terminal-history-gap">
              Some earlier terminal output is unavailable.
            </Text>
          ) : null}
          {error !== undefined && !sessionLimitReached ? <Text style={styles.error} testID="terminal-error">{terminalErrorMessage(error)}</Text> : null}
          <TerminalControls
            altActive={altActive}
            ctrlActive={ctrlActive}
            keyboardVisible={keyboardVisible}
            onArrow={sendArrow}
            onEscape={sendEscape}
            onKeyboardToggle={toggleTerminalKeyboard}
            onNavigation={sendNavigation}
            onReturn={submitTerminalInput}
            onTerminalKey={sendTerminalKey}
            onToggleAlt={toggleAlt}
            onToggleCtrl={toggleCtrl}
            onToggleTranscriptPager={toggleCodexTranscriptPager}
            running={running}
            toolchain={toolchain}
            transcriptPagerOpen={transcriptPagerOpen}
          />
        </KeyboardAvoidingView>
      </View>
    </>
  );
}

const styles = StyleSheet.create({
  safeArea: {backgroundColor: TERMINAL_BACKGROUND, flex: 1, paddingTop: Platform.OS === 'android' ? StatusBar.currentHeight ?? 0 : 0},
  keyboardRoot: {flex: 1},
  floatingHeader: {alignItems: 'center', flexDirection: 'row', justifyContent: 'flex-end', left: 0, paddingHorizontal: 12, position: 'absolute', right: 0, top: 8, zIndex: 10},
  floatingGroup: {alignItems: 'center', borderColor: 'rgba(48, 56, 59, 0.65)', borderRadius: 22, borderWidth: 1, flexDirection: 'row', height: 44, justifyContent: 'center', paddingHorizontal: 5},
  menuArrowText: {color: terminalChrome.accent, fontFamily: UI_FONT_FAMILY, fontSize: 20, fontWeight: '800', includeFontPadding: false, lineHeight: 22, textAlign: 'center', width: 34},
  output: {backgroundColor: TERMINAL_BACKGROUND, flex: 1, overflow: 'hidden'},
  sessionLimitOverlay: {alignItems: 'center', backgroundColor: 'rgba(13, 17, 18, 0.82)', bottom: 0, justifyContent: 'center', left: 0, padding: 18, position: 'absolute', right: 0, top: 0, zIndex: 5},
  sessionLimitCard: {backgroundColor: '#182022', borderColor: terminalChrome.danger, borderRadius: 12, borderWidth: 1, maxWidth: 520, paddingHorizontal: 20, paddingVertical: 18, width: '100%'},
  sessionLimitTitle: {color: terminalChrome.danger, fontFamily: UI_FONT_FAMILY, fontSize: 17, fontWeight: '800', textAlign: 'center'},
  sessionLimitMessage: {color: TERMINAL_FOREGROUND, fontFamily: UI_FONT_FAMILY, fontSize: 13, lineHeight: 19, marginTop: 10, textAlign: 'center'},
  sessionLimitDetails: {color: terminalChrome.muted, fontFamily: UI_FONT_FAMILY, fontSize: 10, lineHeight: 16, marginTop: 14, textAlign: 'center'},
  sessionLimitSessions: {gap: 8, marginTop: 14},
  sessionLimitSessionRow: {alignItems: 'center', backgroundColor: terminalChrome.panel, borderColor: terminalChrome.border, borderRadius: 8, borderWidth: 1, flexDirection: 'row', minHeight: 58, paddingHorizontal: 9, paddingVertical: 7},
  sessionLimitSessionCopy: {flex: 1, minWidth: 0},
  sessionLimitSessionTitle: {color: TERMINAL_FOREGROUND, fontFamily: UI_FONT_FAMILY, fontSize: 12, fontWeight: '800'},
  sessionLimitSessionMeta: {color: terminalChrome.muted, fontFamily: UI_FONT_FAMILY, fontSize: 8, letterSpacing: 0.2, marginTop: 4},
  sessionLimitTerminate: {alignItems: 'center', borderColor: terminalChrome.danger, borderRadius: 6, borderWidth: 1, justifyContent: 'center', marginLeft: 8, minHeight: 32, minWidth: 78, paddingHorizontal: 7},
  sessionLimitTerminateDisabled: {borderColor: terminalChrome.muted, opacity: 0.7},
  sessionLimitTerminateText: {color: terminalChrome.danger, fontFamily: UI_FONT_FAMILY, fontSize: 8, fontWeight: '800', letterSpacing: 0.1},
  sessionLimitActionError: {color: terminalChrome.danger, fontFamily: UI_FONT_FAMILY, fontSize: 9, lineHeight: 15, marginTop: 12, textAlign: 'center'},
  error: {color: terminalChrome.danger, fontFamily: UI_FONT_FAMILY, fontSize: 11, paddingHorizontal: 18, paddingTop: 8},
  historyNotice: {color: terminalChrome.muted, fontFamily: UI_FONT_FAMILY, fontSize: 11, paddingHorizontal: 18, paddingTop: 8},
  keyboardInput: {backgroundColor: 'transparent', bottom: 98, color: 'transparent', height: 34, left: 14, opacity: 0.02, padding: 0, position: 'absolute', right: 14, zIndex: 3},
  controls: {backgroundColor: TERMINAL_BACKGROUND, paddingVertical: 4},
  controlRows: {width: '100%'},
  controlRow: {flexDirection: 'row', height: 42, width: '100%'},
  controlRowMain: {borderTopColor: terminalChrome.border, borderTopWidth: 1, flex: 1, flexDirection: 'row', minWidth: 0},
  keyButton: {alignItems: 'center', backgroundColor: TERMINAL_BACKGROUND, borderRightColor: terminalChrome.borderSoft, borderRightWidth: 1, flex: 1, justifyContent: 'center', minWidth: 0, paddingHorizontal: 0},
  keyButtonText: {color: TERMINAL_FOREGROUND, fontFamily: UI_FONT_FAMILY, fontSize: 10, fontWeight: '800'},
  modifierButtonActive: {backgroundColor: terminalChrome.accent},
  modifierButtonTextActive: {color: TERMINAL_BACKGROUND},
  returnKeyUpper: {backgroundColor: TERMINAL_BACKGROUND, borderColor: terminalChrome.border, borderLeftWidth: 1, borderRightWidth: 1, borderTopWidth: 1, height: 42, width: 72},
  returnKeyLower: {alignItems: 'center', backgroundColor: TERMINAL_BACKGROUND, borderBottomColor: terminalChrome.border, borderBottomWidth: 1, borderColor: terminalChrome.border, borderLeftWidth: 1, borderRightWidth: 1, borderTopColor: TERMINAL_BACKGROUND, borderTopWidth: 1, flexDirection: 'row', height: 42, justifyContent: 'center', position: 'relative', width: 96},
  returnKeyNotch: {backgroundColor: terminalChrome.border, height: 1, left: 0, position: 'absolute', top: 0, width: 24},
  returnKeyGlyph: {color: terminalChrome.accent, fontFamily: UI_FONT_FAMILY, fontSize: 25, fontWeight: '800', lineHeight: 28},
  returnKeyText: {color: TERMINAL_FOREGROUND, fontFamily: UI_FONT_FAMILY, fontSize: 9, fontWeight: '800', letterSpacing: 0.3, marginLeft: 4},
});

export default TerminalScreen;
