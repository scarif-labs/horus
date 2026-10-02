import React from 'react';
import {ScrollView, StyleSheet, Text, View} from 'react-native';
import {ScreenShell} from '../screen/ScreenShell';
import {BackgroundPermissionsPanel} from './BackgroundPermissions';
import {BrandHeader, uiColors} from './brand';
import {InteractivePressable as Pressable} from './InteractivePressable';
import {RemoteAccessPanel} from './RemoteAccessPanel';
import {SettingsAction, SettingsRow, SettingsSection} from './SettingsList';
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
          eyebrow="HORUS"
          title="Settings"
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

        <SettingsSection
          footer={error === undefined ? (
            <Text style={styles.footer} testID="settings-session-limit-current">
              {loading ? 'Loading…' : settings === undefined ? 'Unavailable' : `Up to ${limitLabel(settings.maxConcurrentSessions)} at once. Each keeps its own notification while Horus is in the background.`}
            </Text>
          ) : (
            <View style={styles.errorRow}>
              <Text style={styles.error} testID="settings-error">{error}</Text>
              {loading ? null : <SettingsAction label="RETRY" onPress={() => { void refresh(); }} testID="settings-retry" tone="danger" />}
            </View>
          )}
          title="SESSIONS">
          <SettingsRow
            icon="layers"
            label="Apps at once"
            right={(
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
                    </Pressable>
                  );
                })}
              </View>
            )}
          />
        </SettingsSection>

        <BackgroundPermissionsPanel />
        <RemoteAccessPanel />
      </ScrollView>
    </ScreenShell>
  );
}

const styles = StyleSheet.create({
  content: {paddingHorizontal: 18, paddingBottom: 28},
  options: {backgroundColor: uiColors.background, borderColor: uiColors.borderSoft, borderRadius: 8, borderWidth: 1, flexDirection: 'row', gap: 2, marginLeft: 10, padding: 2},
  option: {alignItems: 'center', borderRadius: 6, height: 30, justifyContent: 'center', width: 32},
  optionSelected: {backgroundColor: uiColors.accent},
  optionDisabled: {opacity: 0.55},
  optionValue: {color: uiColors.muted, fontFamily: UI_FONT_FAMILY, fontSize: 12, fontWeight: '800'},
  optionValueSelected: {color: uiColors.background},
  footer: {color: uiColors.subdued, fontFamily: UI_FONT_FAMILY, fontSize: 9, lineHeight: 14, marginHorizontal: 4, marginTop: 8},
  errorRow: {alignItems: 'center', flexDirection: 'row', marginHorizontal: 4, marginTop: 8},
  error: {color: uiColors.danger, flex: 1, fontFamily: UI_FONT_FAMILY, fontSize: 9, lineHeight: 14},
  backButton: {alignItems: 'center', borderColor: uiColors.border, borderRadius: 7, borderWidth: 1, justifyContent: 'center', minHeight: 32, minWidth: 58, paddingHorizontal: 8},
  backText: {color: uiColors.accent, fontFamily: UI_FONT_FAMILY, fontSize: 9, fontWeight: '800'},
});
