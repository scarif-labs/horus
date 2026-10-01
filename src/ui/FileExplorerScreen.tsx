import React from 'react';
import {ScrollView, StyleSheet, Text, View} from 'react-native';
import {UI_FONT_FAMILY} from './typography';
import {useHardwareBack} from './useHardwareBack';
import {
  exportGuestDirectory,
  GUEST_FILE_EXPORT_MAX_FILES,
  listGuestDirectory,
  readGuestTextFile,
  type GuestExportResult,
  type GuestFileEntry,
  type GuestFilePath,
  type GuestFileRoot,
} from '../files/fileExplorer';
import {ScreenShell} from '../screen/ScreenShell';
import {BrandHeader, uiColors} from './brand';
import {InteractivePressable as Pressable} from './InteractivePressable';

function formatSize(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 ** 2) return `${Math.ceil(bytes / 1024)} KB`;
  return `${(bytes / 1024 ** 2).toFixed(1)} MB`;
}

function errorMessage(code: string): string {
  if (code === 'timeout') return 'The file query timed out. Refresh to try again.';
  if (code === 'session_limit_reached') return 'The file query could not start beside the active terminal. Refresh to retry.';
  if (code === 'not_found') return 'That file or folder is no longer available.';
  if (code === 'too_large') return 'This file is too large to preview here.';
  if (code === 'binary_or_invalid_text') return 'This file is not valid UTF-8 text.';
  if (code === 'invalid_path') return 'That path cannot be opened.';
  if (code === 'command_failed') return 'The guest shell could not list this folder. Refresh to retry.';
  if (code === 'invalid_output') return 'The guest returned an unreadable folder response. Refresh to retry.';
  return 'Could not read the workspace. Refresh to try again.';
}

function exportMessage(result: GuestExportResult): string {
  if (result.kind === 'success') {
    const files = result.fileCount === 1 ? '1 file' : `${result.fileCount} files`;
    const skipped = result.skippedCount === 0
      ? ''
      : ` ${result.skippedCount} item${result.skippedCount === 1 ? ' was' : 's were'} skipped (symlinks, special files, or unreadable files).`;
    return `Copied ${files} (${formatSize(result.byteCount)}) to ${result.destination}.${skipped}`;
  }
  const code = result.errorCode;
  if (code === 'permission_denied') return 'Storage permission is needed to copy into Downloads.';
  if (code === 'too_large') return `This folder is too large to copy (over ${GUEST_FILE_EXPORT_MAX_FILES.toLocaleString('en-US')} files or 2 GB).`;
  if (code === 'busy') return 'Another copy is still running. Try again when it finishes.';
  if (code === 'unsupported') return 'Copying to Downloads is not available on this device.';
  if (code === 'not_found') return 'That folder is no longer available.';
  return 'Could not copy this folder to Downloads.';
}

function pathLabel(root: GuestFileRoot, path: GuestFilePath): string {
  const rootLabel = root === 'home' ? '~' : '/workspace';
  return path.length === 0 ? rootLabel : `${rootLabel}/${path.join('/')}`;
}

function FileRow({entry, onPress}: {entry: GuestFileEntry; onPress: () => void}): React.JSX.Element {
  const isOpenable = entry.kind === 'directory' || entry.kind === 'file';
  const glyph = entry.kind === 'directory' ? '▰' : entry.kind === 'file' ? '·' : entry.kind === 'symlink' ? '↗' : '—';
  return (
    <Pressable
      accessibilityLabel={`${entry.kind}, ${entry.name}${entry.kind === 'file' ? `, ${formatSize(entry.sizeBytes)}` : ''}`}
      accessibilityRole="button"
      accessibilityState={{disabled: !isOpenable}}
      disabled={!isOpenable}
      onPress={onPress}
      style={[styles.fileRow, !isOpenable && styles.fileRowDisabled]}
      testID={`file-entry-${entry.kind}`}>
      <Text style={[styles.fileGlyph, entry.kind === 'directory' && styles.folderGlyph]}>{glyph}</Text>
      <View style={styles.fileCopy}>
        <Text numberOfLines={1} style={styles.fileName}>{entry.name}</Text>
        <Text style={styles.fileMeta}>{entry.kind === 'file' ? formatSize(entry.sizeBytes) : entry.kind.toUpperCase()}</Text>
      </View>
      {isOpenable ? <Text style={styles.rowArrow}>{entry.kind === 'directory' ? '→' : 'OPEN'}</Text> : null}
    </Pressable>
  );
}

export function FileExplorerScreen({onBack}: {onBack: () => void}): React.JSX.Element {
  const [root, setRoot] = React.useState<GuestFileRoot>('home');
  const [path, setPath] = React.useState<GuestFilePath>([]);
  const [entries, setEntries] = React.useState<readonly GuestFileEntry[]>([]);
  const [truncated, setTruncated] = React.useState(false);
  const [hiddenInvalidNameCount, setHiddenInvalidNameCount] = React.useState(0);
  const [loading, setLoading] = React.useState(true);
  const [error, setError] = React.useState<string | undefined>();
  const [previewName, setPreviewName] = React.useState<string | undefined>();
  const [preview, setPreview] = React.useState<string | undefined>();
  const [previewLoading, setPreviewLoading] = React.useState(false);
  const [previewError, setPreviewError] = React.useState<string | undefined>();
  const [exportConfirming, setExportConfirming] = React.useState(false);
  const [exporting, setExporting] = React.useState(false);
  const [exportResult, setExportResult] = React.useState<GuestExportResult | undefined>();
  const mountedRef = React.useRef(true);
  const querySequenceRef = React.useRef(0);

  React.useEffect(() => {
    mountedRef.current = true;
    return () => {
      mountedRef.current = false;
      querySequenceRef.current += 1;
    };
  }, []);

  const refresh = React.useCallback(async (nextRoot: GuestFileRoot, nextPath: GuestFilePath) => {
    const sequence = ++querySequenceRef.current;
    setLoading(true);
    setError(undefined);
    setHiddenInvalidNameCount(0);
    const result = await listGuestDirectory(nextRoot, nextPath);
    if (!mountedRef.current || sequence !== querySequenceRef.current) return;
    if (result.kind === 'success') {
      setEntries(result.entries);
      setTruncated(result.truncated);
      setHiddenInvalidNameCount(result.hiddenInvalidNameCount);
      setError(undefined);
    } else {
      setEntries([]);
      setTruncated(false);
      setHiddenInvalidNameCount(0);
      setError(errorMessage(result.errorCode));
    }
    setLoading(false);
  }, []);

  React.useEffect(() => {
    void refresh(root, path);
    setExportConfirming(false);
    setExportResult(undefined);
  }, [path, refresh, root]);

  const copyToDownloads = async () => {
    setExportConfirming(false);
    setExportResult(undefined);
    setExporting(true);
    const result = await exportGuestDirectory(root, path);
    if (!mountedRef.current) return;
    setExporting(false);
    setExportResult(result);
  };

  const changeRoot = (nextRoot: GuestFileRoot) => {
    querySequenceRef.current += 1;
    setPreviewName(undefined);
    setPreview(undefined);
    setPreviewError(undefined);
    setPath([]);
    setRoot(nextRoot);
  };

  const openEntry = async (entry: GuestFileEntry) => {
    if (entry.kind === 'directory') {
      querySequenceRef.current += 1;
      setPreviewName(undefined);
      setPreview(undefined);
      setPreviewError(undefined);
      setPath(current => [...current, entry.name]);
      return;
    }
    if (entry.kind !== 'file') return;
    const sequence = ++querySequenceRef.current;
    setPreviewName(entry.name);
    setPreview(undefined);
    setPreviewError(undefined);
    setPreviewLoading(true);
    const result = await readGuestTextFile(root, [...path, entry.name]);
    if (!mountedRef.current || sequence !== querySequenceRef.current) return;
    if (result.kind === 'success') {
      setPreview(result.content);
      setPreviewError(undefined);
    } else {
      setPreviewError(errorMessage(result.errorCode));
    }
    setPreviewLoading(false);
  };

  const closePreview = React.useCallback(() => {
    querySequenceRef.current += 1;
    setPreviewName(undefined);
    setPreview(undefined);
    setPreviewError(undefined);
    setPreviewLoading(false);
  }, []);

  const goUp = React.useCallback(() => {
    if (path.length === 0) return;
    querySequenceRef.current += 1;
    closePreview();
    setPath(current => current.slice(0, -1));
  }, [closePreview, path.length]);

  const handleHardwareBack = React.useCallback(() => {
    if (previewName !== undefined) {
      closePreview();
      return true;
    }
    if (path.length > 0) {
      goUp();
      return true;
    }
    onBack();
    return true;
  }, [closePreview, goUp, onBack, path.length, previewName]);
  useHardwareBack(true, handleHardwareBack);

  return (
    <ScreenShell testID="file-explorer-screen">
      <ScrollView contentContainerStyle={styles.content}>
        <BrandHeader
          eyebrow="LOCAL"
          title="Workspace"
          meta={null}
          action={(
            <Pressable
              accessibilityLabel="Back to home"
              accessibilityRole="button"
              onPress={onBack}
              style={styles.menuArrow}
              testID="file-explorer-back">
              <Text style={styles.menuArrowText}>menu ^</Text>
            </Pressable>
          )}
        />

        <View style={styles.rootSelector}>
          <Pressable accessibilityRole="button" accessibilityState={{selected: root === 'home'}} onPress={() => changeRoot('home')} style={[styles.rootButton, root === 'home' && styles.rootButtonSelected]} testID="file-root-home">
            <Text style={[styles.rootText, root === 'home' && styles.rootTextSelected]}>HOME</Text>
            <Text style={[styles.rootSubtext, root === 'home' && styles.rootTextSelected]}>~</Text>
          </Pressable>
          <Pressable accessibilityRole="button" accessibilityState={{selected: root === 'workspace'}} onPress={() => changeRoot('workspace')} style={[styles.rootButton, root === 'workspace' && styles.rootButtonSelected]} testID="file-root-workspace">
            <Text style={[styles.rootText, root === 'workspace' && styles.rootTextSelected]}>WORKSPACE</Text>
            <Text style={[styles.rootSubtext, root === 'workspace' && styles.rootTextSelected]}>/workspace</Text>
          </Pressable>
        </View>

        {previewName === undefined ? (
          <>
            <View style={styles.pathRow}>
              <View style={styles.pathCopy}>
                <Text style={styles.sectionLabel}>CURRENT FOLDER</Text>
                <Text numberOfLines={2} style={styles.pathText} testID="file-current-path">{pathLabel(root, path)}</Text>
              </View>
              <Pressable accessibilityLabel="Refresh folder" accessibilityRole="button" disabled={loading} onPress={() => void refresh(root, path)} style={styles.refreshButton} testID="file-refresh">
                <Text style={styles.refreshText}>{loading ? '…' : '↻'}</Text>
              </Pressable>
            </View>
            {exportConfirming ? (
              <View style={styles.exportPanel} testID="file-export-confirm">
                <Text style={styles.exportPrompt}>
                  Copy everything in {pathLabel(root, path)} to Download/Horus? Hidden files such as .env or tokens are included and become readable by other apps.
                </Text>
                <View style={styles.exportActions}>
                  <Pressable accessibilityRole="button" onPress={() => setExportConfirming(false)} style={styles.exportSecondary} testID="file-export-cancel">
                    <Text style={styles.exportSecondaryText}>CANCEL</Text>
                  </Pressable>
                  <Pressable accessibilityRole="button" onPress={() => { void copyToDownloads(); }} style={styles.exportPrimary} testID="file-export-run">
                    <Text style={styles.exportPrimaryText}>COPY</Text>
                  </Pressable>
                </View>
              </View>
            ) : (
              <Pressable
                accessibilityLabel="Copy this folder to Downloads"
                accessibilityRole="button"
                accessibilityState={{disabled: exporting || loading || error !== undefined}}
                disabled={exporting || loading || error !== undefined}
                onPress={() => setExportConfirming(true)}
                style={[styles.exportButton, (exporting || loading || error !== undefined) && styles.fileRowDisabled]}
                testID="file-export">
                <Text style={styles.exportButtonText}>{exporting ? 'COPYING TO DOWNLOADS…' : '⇩  COPY THIS FOLDER TO DOWNLOADS'}</Text>
              </Pressable>
            )}
            {exportResult !== undefined ? (
              <Text style={exportResult.kind === 'success' ? styles.exportDone : styles.error} testID="file-export-result">
                {exportMessage(exportResult)}
              </Text>
            ) : null}
            {path.length > 0 ? (
              <Pressable accessibilityRole="button" onPress={goUp} style={styles.parentRow} testID="file-parent-folder">
                <Text style={styles.fileGlyph}>↑</Text>
                <Text style={styles.parentName}>..</Text>
                <Text style={styles.rowArrow}>UP</Text>
              </Pressable>
            ) : null}
            {loading ? <Text style={styles.note}>Loading folder…</Text> : null}
            {error !== undefined ? <Text style={styles.error} testID="file-explorer-error">{error}</Text> : null}
            {!loading && error === undefined && entries.length === 0 ? <Text style={styles.empty}>This folder is empty.</Text> : null}
            {entries.map((entry, index) => (
              <FileRow key={`${entry.kind}-${entry.name}-${index}`} entry={entry} onPress={() => { void openEntry(entry); }} />
            ))}
            {truncated ? <Text style={styles.note}>Showing the first 200 entries.</Text> : null}
            {hiddenInvalidNameCount > 0 ? (
              <Text style={styles.note} testID="file-hidden-invalid-names">
                {hiddenInvalidNameCount === 1
                  ? '1 entry with a non-UTF-8 name is hidden.'
                  : `${hiddenInvalidNameCount} entries with non-UTF-8 names are hidden.`}
              </Text>
            ) : null}
            <Text style={styles.note}>Text previews are limited to 64 KB. Symbolic links are shown but not opened.</Text>
          </>
        ) : (
          <>
            <View style={styles.previewHeading}>
              <Pressable accessibilityRole="button" onPress={closePreview} style={styles.previewBack} testID="file-preview-back">
                <Text style={styles.backText}>←  FILES</Text>
              </Pressable>
              <Text numberOfLines={2} style={styles.previewName}>{previewName}</Text>
              <Text numberOfLines={1} style={styles.previewPath}>{pathLabel(root, path)}</Text>
            </View>
            {previewLoading ? <Text style={styles.empty}>Loading preview…</Text> : null}
            {previewError !== undefined ? <Text style={styles.error} testID="file-preview-error">{previewError}</Text> : null}
            {preview !== undefined ? <Text selectable style={styles.previewText} testID="file-preview-content">{preview.length === 0 ? '(empty file)' : preview}</Text> : null}
          </>
        )}
      </ScrollView>
    </ScreenShell>
  );
}

const styles = StyleSheet.create({
  content: {paddingHorizontal: 18, paddingBottom: 26},
  menuArrow: {alignItems: 'center', borderColor: uiColors.border, borderRadius: 9, borderWidth: 1, height: 38, justifyContent: 'center', marginLeft: 8, paddingHorizontal: 10},
  menuArrowText: {color: uiColors.accent, fontFamily: UI_FONT_FAMILY, fontSize: 12, fontWeight: '800'},
  backText: {color: uiColors.accent, fontFamily: UI_FONT_FAMILY, fontSize: 10, fontWeight: '800', letterSpacing: 0.4},
  rootSelector: {flexDirection: 'row', gap: 8, marginBottom: 16},
  rootButton: {alignItems: 'flex-start', backgroundColor: uiColors.panel, borderColor: uiColors.border, borderRadius: 9, borderWidth: 1, flex: 1, justifyContent: 'center', minHeight: 56, paddingHorizontal: 12},
  rootButtonSelected: {borderColor: uiColors.accent, backgroundColor: '#172018'},
  rootText: {color: uiColors.muted, fontFamily: UI_FONT_FAMILY, fontSize: 10, fontWeight: '800', letterSpacing: 0.5},
  rootTextSelected: {color: uiColors.accent},
  rootSubtext: {color: uiColors.subdued, fontFamily: UI_FONT_FAMILY, fontSize: 9, marginTop: 4},
  pathRow: {alignItems: 'center', flexDirection: 'row', marginBottom: 9},
  pathCopy: {flex: 1, minWidth: 0},
  sectionLabel: {color: uiColors.muted, fontFamily: UI_FONT_FAMILY, fontSize: 9, letterSpacing: 0.8},
  pathText: {color: uiColors.ink, fontFamily: UI_FONT_FAMILY, fontSize: 12, fontWeight: '700', marginTop: 5},
  refreshButton: {alignItems: 'center', borderColor: uiColors.border, borderRadius: 7, borderWidth: 1, height: 38, justifyContent: 'center', marginLeft: 10, width: 42},
  refreshText: {color: uiColors.accent, fontFamily: UI_FONT_FAMILY, fontSize: 22, fontWeight: '800'},
  exportButton: {alignItems: 'center', borderColor: uiColors.border, borderRadius: 8, borderWidth: 1, justifyContent: 'center', marginBottom: 9, minHeight: 42, paddingHorizontal: 12},
  exportButtonText: {color: uiColors.accent, fontFamily: UI_FONT_FAMILY, fontSize: 10, fontWeight: '800', letterSpacing: 0.4},
  exportPanel: {backgroundColor: uiColors.panel, borderColor: uiColors.accent, borderRadius: 8, borderWidth: 1, marginBottom: 9, padding: 12},
  exportPrompt: {color: uiColors.ink, fontFamily: UI_FONT_FAMILY, fontSize: 10, lineHeight: 16},
  exportActions: {flexDirection: 'row', gap: 8, justifyContent: 'flex-end', marginTop: 10},
  exportSecondary: {alignItems: 'center', borderColor: uiColors.border, borderRadius: 7, borderWidth: 1, justifyContent: 'center', minHeight: 36, paddingHorizontal: 14},
  exportSecondaryText: {color: uiColors.muted, fontFamily: UI_FONT_FAMILY, fontSize: 10, fontWeight: '800'},
  exportPrimary: {alignItems: 'center', backgroundColor: uiColors.accent, borderRadius: 7, justifyContent: 'center', minHeight: 36, paddingHorizontal: 18},
  exportPrimaryText: {color: '#090D0B', fontFamily: UI_FONT_FAMILY, fontSize: 10, fontWeight: '800'},
  exportDone: {backgroundColor: '#172018', borderColor: uiColors.accent, borderRadius: 8, borderWidth: 1, color: uiColors.ink, fontFamily: UI_FONT_FAMILY, fontSize: 10, lineHeight: 16, marginBottom: 9, padding: 12},
  parentRow: {alignItems: 'center', backgroundColor: '#121715', borderColor: uiColors.border, borderRadius: 8, borderWidth: 1, flexDirection: 'row', marginBottom: 7, minHeight: 48, paddingHorizontal: 12},
  parentName: {color: uiColors.muted, flex: 1, fontFamily: UI_FONT_FAMILY, fontSize: 12, marginLeft: 7},
  fileRow: {alignItems: 'center', backgroundColor: uiColors.panel, borderColor: uiColors.border, borderRadius: 8, borderWidth: 1, flexDirection: 'row', marginBottom: 7, minHeight: 58, paddingHorizontal: 12},
  fileRowDisabled: {opacity: 0.65},
  fileGlyph: {color: uiColors.muted, fontFamily: UI_FONT_FAMILY, fontSize: 20, fontWeight: '800', textAlign: 'center', width: 27},
  folderGlyph: {color: uiColors.accent},
  fileCopy: {flex: 1, minWidth: 0, paddingVertical: 8},
  fileName: {color: uiColors.ink, fontFamily: UI_FONT_FAMILY, fontSize: 12, fontWeight: '700'},
  fileMeta: {color: uiColors.subdued, fontFamily: UI_FONT_FAMILY, fontSize: 8, letterSpacing: 0.3, marginTop: 4},
  rowArrow: {color: uiColors.accent, fontFamily: UI_FONT_FAMILY, fontSize: 8, fontWeight: '800', marginLeft: 8},
  error: {backgroundColor: '#251515', borderColor: '#5C2929', borderRadius: 8, borderWidth: 1, color: uiColors.danger, fontFamily: UI_FONT_FAMILY, fontSize: 10, lineHeight: 16, marginTop: 8, padding: 12},
  empty: {backgroundColor: uiColors.panel, borderColor: uiColors.border, borderRadius: 8, borderWidth: 1, color: uiColors.muted, fontFamily: UI_FONT_FAMILY, fontSize: 10, lineHeight: 17, marginTop: 4, padding: 13},
  note: {color: uiColors.subdued, fontFamily: UI_FONT_FAMILY, fontSize: 8, lineHeight: 13, marginTop: 8},
  previewHeading: {backgroundColor: uiColors.panel, borderColor: uiColors.border, borderRadius: 9, borderWidth: 1, marginTop: 2, padding: 12},
  previewBack: {alignSelf: 'flex-start', paddingVertical: 4},
  previewName: {color: uiColors.ink, fontFamily: UI_FONT_FAMILY, fontSize: 14, fontWeight: '800', marginTop: 8},
  previewPath: {color: uiColors.muted, fontFamily: UI_FONT_FAMILY, fontSize: 8, marginTop: 5},
  previewText: {backgroundColor: '#090D0B', borderColor: uiColors.border, borderRadius: 8, borderWidth: 1, color: uiColors.ink, fontFamily: UI_FONT_FAMILY, fontSize: 10, lineHeight: 16, marginTop: 10, padding: 12},
});
