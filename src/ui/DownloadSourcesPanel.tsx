import React from 'react';
import {StyleSheet, Text, View} from 'react-native';
import {
  ALPINE_MIRRORS,
  NPM_REGISTRIES,
  readDownloadSources,
  writeDownloadSources,
  type DownloadSources,
  type DownloadSourcesResult,
  type MirrorOption,
} from '../terminal/downloadSources';
import {uiColors} from './brand';
import {MirrorPicker} from './MirrorPicker';
import {SettingsRow, SettingsSection} from './SettingsList';
import {UI_FONT_FAMILY} from './typography';

type DownloadSourcesPanelProps = Readonly<{
  read?: () => Promise<DownloadSourcesResult>;
  write?: (sources: DownloadSources) => Promise<DownloadSourcesResult>;
}>;

type SourceKey = keyof DownloadSources;

function sourceLabel(options: readonly MirrorOption[], url: string | undefined): string {
  const option = options.find(item => item.url === url);
  if (option !== undefined) return `${option.label} · ${option.region}`;
  return url?.replace(/^https:\/\//, '') ?? '';
}

/** Mirrors for networks that cannot reach Alpine's or npm's servers. */
export function DownloadSourcesPanel({read = readDownloadSources, write = writeDownloadSources}: DownloadSourcesPanelProps): React.JSX.Element {
  const [sources, setSources] = React.useState<DownloadSources | undefined>();
  const [open, setOpen] = React.useState<SourceKey | undefined>();
  const [saving, setSaving] = React.useState(false);
  const [error, setError] = React.useState(false);
  const mountedRef = React.useRef(true);

  React.useEffect(() => {
    mountedRef.current = true;
    read().then(result => {
      if (!mountedRef.current) return;
      if (result.kind === 'success') setSources(result.sources);
      else setError(true);
    }).catch(() => undefined);
    return () => { mountedRef.current = false; };
  }, [read]);

  const choose = (key: SourceKey, url: string | undefined) => {
    if (sources === undefined || saving) return;
    setSaving(true);
    setError(false);
    write({...sources, [key]: url}).then(result => {
      if (!mountedRef.current) return;
      if (result.kind === 'success') setSources(result.sources);
      else setError(true);
      setSaving(false);
    }).catch(() => undefined);
  };

  const row = (key: SourceKey, label: string, options: readonly MirrorOption[], placeholder: string) => (
    <SettingsRow
      accessibilityLabel={`${label} download source`}
      below={open === key && sources !== undefined ? (
        <View style={styles.picker}>
          <MirrorPicker disabled={saving} onSelect={url => choose(key, url)} options={options} placeholder={placeholder} testID={`settings-${key}`} value={sources[key]} />
        </View>
      ) : undefined}
      detail={sources === undefined ? 'Loading…' : sourceLabel(options, sources[key])}
      label={label}
      onPress={() => setOpen(current => current === key ? undefined : key)}
      right={<Text style={styles.chevron}>{open === key ? '▴' : '▾'}</Text>}
      testID={`settings-${key}-row`} />
  );

  return (
    <SettingsSection
      footer={error ? 'Could not save the download source. Try again.' : 'Mirrors are copies of the official servers, for networks that can’t reach them. Claude Code always downloads from Anthropic.'}
      footerTone={error ? 'danger' : 'muted'}
      title="DOWNLOADS">
      {row('alpineMirror', 'Linux packages', ALPINE_MIRRORS, 'https://mirror.example/alpine')}
      {row('npmRegistry', 'npm packages', NPM_REGISTRIES, 'https://registry.example.com')}
    </SettingsSection>
  );
}

const styles = StyleSheet.create({
  picker: {marginTop: 8},
  chevron: {color: uiColors.muted, fontFamily: UI_FONT_FAMILY, fontSize: 12, marginLeft: 10},
});
