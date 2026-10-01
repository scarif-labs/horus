import React from 'react';
import {ScrollView, StyleSheet, Text, View} from 'react-native';
import {ScreenShell} from '../screen/ScreenShell';
import {BackgroundPermissionsPanel} from './BackgroundPermissions';
import {BrandHeader, uiColors} from './brand';
import {InteractivePressable as Pressable} from './InteractivePressable';
import {RemoteAccessPanel} from './RemoteAccessPanel';
import {UI_FONT_FAMILY} from './typography';
import {useHardwareBack} from './useHardwareBack';
import {
  readSessionSettings,
  TERMINAL_SESSION_LIMIT_MAX,
  TERMINAL_SESSION_LIMIT_MIN,
  writeSessionLimit,
  type SessionSettings,
  type SessionSettingsResult,
} from '../terminal/session/sessionSettings';

type SessionSettingsScreenProps = Readonly<{
  onBack: () => void;
  readSettings?: () => Promise<SessionSettingsResult>;
  saveLimit?: (limit: number) => Promise<SessionSettingsResult>;
}>;

function errorLabel(errorCode: string): string {
  if (errorCode === 'unavailable') return 'The native runtime is unavailable. Reopen Horus and try again.';
  if (errorCode === 'invalid_response') return 'Horus returned an unreadable settings response. Try again.';
  return 'Could not save this setting. Try again.';
}

function limitLabel(limit: number): string {
  return limit === 1 ? '1 app' : `${limit} apps`;
}

export function SessionSettingsScreen({onBack, readSettings = readSessionSettings, saveLimit = writeSessionLimit}: SessionSettingsScreenProps): React.JSX.Element {
  const [settings, setSettings] = React.useState<SessionSettings | undefined>();
  const [loading, setLoading] = React.useState(true);
  const [saving, setSaving] = React.useState(false);
  const [error, setError] = React.useState<string | undefined>();
  const mountedRef = React.useRef(true);

  const refresh = React.useCallback(async () => {
    setLoading(true);
    setError(undefined);
    const result = await readSettings();
    if (!mountedRef.current) return;
    if (result.kind === 'success') {
      setSettings(result.settings);
    } else {
      setError(errorLabel(result.errorCode));
    }
    setLoading(false);
  }, [readSettings]);

  React.useEffect(() => {
    mountedRef.current = true;
    void refresh();
    return () => {
      mountedRef.current = false;
    };
  }, [refresh]);

  useHardwareBack(true, onBack);

  const chooseLimit = React.useCallback(async (limit: number) => {
    if (settings === undefined || saving || limit === settings.maxConcurrentSessions) return;
    setSaving(true);
    setError(undefined);
    const result = await saveLimit(limit);
    if (!mountedRef.current) return;
    if (result.kind === 'success') {
      setSettings(result.settings);
    } else {
      setError(errorLabel(result.errorCode));
    }
    setSaving(false);
  }, [saveLimit, saving, settings]);

  return (
    <ScreenShell testID="session-settings-screen">
      <ScrollView contentContainerStyle={styles.content}>
        <BrandHeader
          eyebrow="SETTINGS"
          title="Runtime"
          meta={null}
          action={(
            <Pressable
              accessibilityLabel="Back"
              accessibilityRole="button"
              onPress={onBack}
              style={styles.backButton}
              testID="settings-back">
              <Text style={styles.backText}>BACK</Text>
            </Pressable>
          )}
        />

        <View style={styles.panel}>
          <Text style={styles.sectionLabel}>CONCURRENT APPS</Text>
          <Text style={styles.title}>How many apps may run at once?</Text>
          <Text style={styles.detail}>Each running terminal or AI app stays alive in its own notification while Horus is in the background.</Text>
          <View style={styles.options}>
            {Array.from({length: TERMINAL_SESSION_LIMIT_MAX - TERMINAL_SESSION_LIMIT_MIN + 1}, (_, index) => index + TERMINAL_SESSION_LIMIT_MIN).map(limit => {
              const selected = settings?.maxConcurrentSessions === limit;
              return (
                <Pressable
                  key={limit}
                  accessibilityLabel={limitLabel(limit)}
                  accessibilityRole="button"
                  accessibilityState={{disabled: loading || saving, selected}}
                  disabled={loading || saving}
                  onPress={() => { void chooseLimit(limit); }}
                  style={[styles.option, selected && styles.optionSelected, (loading || saving) && styles.optionDisabled]}
                  testID={`settings-session-limit-${limit}`}>
                  <Text style={[styles.optionValue, selected && styles.optionValueSelected]}>{limit}</Text>
                  <Text style={[styles.optionLabel, selected && styles.optionLabelSelected]}>{limit === 1 ? 'APP' : 'APPS'}</Text>
                </Pressable>
              );
            })}
          </View>
          <Text style={styles.currentValue} testID="settings-session-limit-current">
            {loading ? 'Loading…' : settings === undefined ? 'Unavailable' : `Current limit: ${limitLabel(settings.maxConcurrentSessions)}`}
          </Text>
          {error === undefined ? null : <Text style={styles.error} testID="settings-error">{error}</Text>}
          {error === undefined || loading ? null : (
            <Pressable accessibilityRole="button" onPress={() => { void refresh(); }} style={styles.retry} testID="settings-retry">
              <Text style={styles.retryText}>RETRY</Text>
            </Pressable>
          )}
        </View>

        <BackgroundPermissionsPanel />

        <RemoteAccessPanel />
      </ScrollView>
    </ScreenShell>
  );
}

const styles = StyleSheet.create({
  content: {paddingHorizontal: 18, paddingBottom: 24},
  panel: {backgroundColor: uiColors.panel, borderColor: uiColors.border, borderRadius: 14, borderWidth: 1, padding: 15},
  sectionLabel: {color: uiColors.muted, fontFamily: UI_FONT_FAMILY, fontSize: 10, letterSpacing: 0.8},
  title: {color: uiColors.ink, fontFamily: UI_FONT_FAMILY, fontSize: 17, fontWeight: '800', marginTop: 12},
  detail: {color: uiColors.muted, fontFamily: UI_FONT_FAMILY, fontSize: 10, lineHeight: 17, marginTop: 8},
  options: {flexDirection: 'row', gap: 8, marginTop: 18},
  option: {alignItems: 'center', backgroundColor: uiColors.background, borderColor: uiColors.border, borderRadius: 10, borderWidth: 1, flex: 1, minHeight: 76, justifyContent: 'center'},
  optionSelected: {backgroundColor: uiColors.accent, borderColor: uiColors.accent},
  optionDisabled: {opacity: 0.55},
  optionValue: {color: uiColors.ink, fontFamily: UI_FONT_FAMILY, fontSize: 22, fontWeight: '800'},
  optionValueSelected: {color: uiColors.background},
  optionLabel: {color: uiColors.muted, fontFamily: UI_FONT_FAMILY, fontSize: 8, letterSpacing: 0.5, marginTop: 4},
  optionLabelSelected: {color: uiColors.background},
  currentValue: {color: uiColors.subdued, fontFamily: UI_FONT_FAMILY, fontSize: 10, marginTop: 15},
  error: {color: uiColors.danger, fontFamily: UI_FONT_FAMILY, fontSize: 10, lineHeight: 16, marginTop: 15},
  retry: {alignItems: 'center', borderColor: uiColors.danger, borderRadius: 7, borderWidth: 1, marginTop: 12, minHeight: 36, justifyContent: 'center'},
  retryText: {color: uiColors.danger, fontFamily: UI_FONT_FAMILY, fontSize: 9, fontWeight: '800', letterSpacing: 0.5},
  backButton: {alignItems: 'center', borderColor: uiColors.border, borderRadius: 7, borderWidth: 1, justifyContent: 'center', minHeight: 32, minWidth: 58, paddingHorizontal: 8},
  backText: {color: uiColors.accent, fontFamily: UI_FONT_FAMILY, fontSize: 9, fontWeight: '800'},
});
