import React from 'react';
import ReactTestRenderer from 'react-test-renderer';
import {BackHandler} from 'react-native';
import {FileExplorerScreen} from '../src/ui/FileExplorerScreen';
import {BrandHeader} from '../src/ui/brand';
import {exportGuestDirectory, listGuestDirectory, readGuestTextFile} from '../src/files/fileExplorer';

jest.mock('../src/files/fileExplorer', () => ({
  listGuestDirectory: jest.fn(async (_root: string, path: readonly string[]) => ({
    kind: 'success',
    entries: path.length === 0
      ? [
          {name: 'projects', kind: 'directory', sizeBytes: 0},
          {name: 'readme.md', kind: 'file', sizeBytes: 12},
          {name: 'private-link', kind: 'symlink', sizeBytes: 0},
        ]
      : [{name: 'source.ts', kind: 'file', sizeBytes: 9}],
    truncated: false,
    hiddenInvalidNameCount: 0,
  })),
  readGuestTextFile: jest.fn(async () => ({kind: 'success', content: 'hello files', sizeBytes: 11})),
  exportGuestDirectory: jest.fn(async () => ({
    kind: 'success',
    destination: 'Download/Horus/projects-20260928-143205',
    fileCount: 2,
    byteCount: 2048,
    skippedCount: 1,
  })),
  GUEST_FILE_EXPORT_MAX_FILES: 20000,
}));

describe('FileExplorerScreen', () => {
  beforeEach(() => jest.clearAllMocks());
  afterEach(() => jest.restoreAllMocks());

  test('keeps a menu back button in the top brand header', async () => {
    const onBack = jest.fn();
    let renderer: ReactTestRenderer.ReactTestRenderer | undefined;
    await ReactTestRenderer.act(async () => {
      renderer = ReactTestRenderer.create(<FileExplorerScreen onBack={onBack} />);
      for (let index = 0; index < 8; index += 1) await Promise.resolve();
    });

    const backButton = renderer?.root.findByProps({testID: 'file-explorer-back'});
    const headerAction = renderer?.root.findByType(BrandHeader).props.action as React.ReactElement<{testID?: string}>;
    expect(headerAction.props.testID).toBe('file-explorer-back');
    expect(backButton?.props.accessibilityLabel).toBe('Return to Metro menu');
    expect(backButton?.props.children.props.children).toBe('menu ^');
    await ReactTestRenderer.act(async () => {
      backButton?.props.onPress();
    });
    expect(onBack).toHaveBeenCalledTimes(1);

    await ReactTestRenderer.act(async () => {
      renderer?.unmount();
      await Promise.resolve();
    });
  });

  test('browses the home and workspace roots and previews regular text files', async () => {
    let renderer: ReactTestRenderer.ReactTestRenderer | undefined;
    await ReactTestRenderer.act(async () => {
      renderer = ReactTestRenderer.create(<FileExplorerScreen onBack={() => undefined} />);
      for (let index = 0; index < 8; index += 1) await Promise.resolve();
    });

    expect(listGuestDirectory).toHaveBeenCalledWith('home', []);
    expect(renderer?.root.findByProps({testID: 'file-current-path'}).props.children).toBe('~');
    expect(renderer?.root.findByProps({testID: 'file-entry-symlink'}).props.accessibilityState.disabled).toBe(true);

    await ReactTestRenderer.act(async () => {
      renderer?.root.findByProps({testID: 'file-entry-directory'}).props.onPress();
      for (let index = 0; index < 8; index += 1) await Promise.resolve();
    });
    expect(listGuestDirectory).toHaveBeenLastCalledWith('home', ['projects']);
    expect(renderer?.root.findByProps({testID: 'file-current-path'}).props.children).toBe('~/projects');

    await ReactTestRenderer.act(async () => {
      renderer?.root.findByProps({testID: 'file-entry-file'}).props.onPress();
      for (let index = 0; index < 8; index += 1) await Promise.resolve();
    });
    expect(readGuestTextFile).toHaveBeenCalledWith('home', ['projects', 'source.ts']);
    expect(renderer?.root.findByProps({testID: 'file-preview-content'}).props.children).toBe('hello files');

    await ReactTestRenderer.act(async () => {
      renderer?.root.findByProps({testID: 'file-preview-back'}).props.onPress();
      renderer?.root.findByProps({testID: 'file-root-workspace'}).props.onPress();
      for (let index = 0; index < 8; index += 1) await Promise.resolve();
    });
    expect(listGuestDirectory).toHaveBeenLastCalledWith('workspace', []);
    expect(renderer?.root.findByProps({testID: 'file-current-path'}).props.children).toBe('/workspace');

    await ReactTestRenderer.act(async () => {
      renderer?.unmount();
      await Promise.resolve();
    });
  });

  test('routes Android Back through preview, parent folder, and Metro and removes its listener', async () => {
    const onBack = jest.fn();
    const subscriptions: Array<{handler: Parameters<typeof BackHandler.addEventListener>[1]; remove: jest.Mock}> = [];
    jest.spyOn(BackHandler, 'addEventListener').mockImplementation((_eventName, handler) => {
      const remove = jest.fn();
      subscriptions.push({handler, remove});
      return {remove};
    });
    let renderer: ReactTestRenderer.ReactTestRenderer | undefined;
    await ReactTestRenderer.act(async () => {
      renderer = ReactTestRenderer.create(<FileExplorerScreen onBack={onBack} />);
      for (let index = 0; index < 8; index += 1) await Promise.resolve();
    });

    await ReactTestRenderer.act(async () => {
      renderer?.root.findByProps({testID: 'file-entry-directory'}).props.onPress();
      for (let index = 0; index < 8; index += 1) await Promise.resolve();
    });
    await ReactTestRenderer.act(async () => {
      expect(subscriptions.at(-1)?.handler({} as never)).toBe(true);
      for (let index = 0; index < 8; index += 1) await Promise.resolve();
    });
    expect(renderer?.root.findByProps({testID: 'file-current-path'}).props.children).toBe('~');

    await ReactTestRenderer.act(async () => {
      renderer?.root.findByProps({testID: 'file-entry-file'}).props.onPress();
      for (let index = 0; index < 8; index += 1) await Promise.resolve();
    });
    await ReactTestRenderer.act(async () => {
      expect(subscriptions.at(-1)?.handler({} as never)).toBe(true);
      await Promise.resolve();
    });
    expect(renderer?.root.findAll(node => node.props.testID === 'file-preview-content').length).toBe(0);
    await ReactTestRenderer.act(async () => {
      expect(subscriptions.at(-1)?.handler({} as never)).toBe(true);
      await Promise.resolve();
    });
    expect(onBack).toHaveBeenCalledTimes(1);

    await ReactTestRenderer.act(async () => {
      renderer?.unmount();
      await Promise.resolve();
    });
    expect(subscriptions.every(subscription => subscription.remove.mock.calls.length > 0)).toBe(true);
  });

  test('explains a guest shell failure and retries when refreshed', async () => {
    jest.mocked(listGuestDirectory)
      .mockResolvedValueOnce({kind: 'error', errorCode: 'command_failed'})
      .mockResolvedValueOnce({kind: 'success', entries: [], truncated: false, hiddenInvalidNameCount: 0});

    let renderer: ReactTestRenderer.ReactTestRenderer | undefined;
    await ReactTestRenderer.act(async () => {
      renderer = ReactTestRenderer.create(<FileExplorerScreen onBack={() => undefined} />);
      for (let index = 0; index < 8; index += 1) await Promise.resolve();
    });
    expect(renderer?.root.findByProps({testID: 'file-explorer-error'}).props.children)
      .toBe('The guest shell could not list this folder. Refresh to retry.');

    await ReactTestRenderer.act(async () => {
      renderer?.root.findByProps({testID: 'file-refresh'}).props.onPress();
      for (let index = 0; index < 8; index += 1) await Promise.resolve();
    });
    expect(listGuestDirectory).toHaveBeenCalledTimes(2);
    expect(renderer?.root.findAll(node => node.props.testID === 'file-explorer-error')).toHaveLength(0);
    expect(renderer?.root.findByProps({testID: 'file-explorer-screen'})).toBeTruthy();

    await ReactTestRenderer.act(async () => {
      renderer?.unmount();
      await Promise.resolve();
    });
  });

  test('shows when entries have names it cannot safely represent', async () => {
    jest.mocked(listGuestDirectory).mockResolvedValueOnce({
      kind: 'success',
      entries: [{name: 'readme.md', kind: 'file', sizeBytes: 12}],
      truncated: false,
      hiddenInvalidNameCount: 1,
    });

    let renderer: ReactTestRenderer.ReactTestRenderer | undefined;
    await ReactTestRenderer.act(async () => {
      renderer = ReactTestRenderer.create(<FileExplorerScreen onBack={() => undefined} />);
      for (let index = 0; index < 8; index += 1) await Promise.resolve();
    });

    expect(renderer?.root.findByProps({testID: 'file-hidden-invalid-names'}).props.children)
      .toBe('1 entry with a non-UTF-8 name is hidden.');
    await ReactTestRenderer.act(async () => {
      renderer?.unmount();
      await Promise.resolve();
    });
  });

  test('copies the current folder to Downloads after confirmation', async () => {
    let renderer: ReactTestRenderer.ReactTestRenderer | undefined;
    await ReactTestRenderer.act(async () => {
      renderer = ReactTestRenderer.create(<FileExplorerScreen onBack={() => undefined} />);
      for (let index = 0; index < 8; index += 1) await Promise.resolve();
    });
    await ReactTestRenderer.act(async () => {
      renderer?.root.findByProps({testID: 'file-entry-directory'}).props.onPress();
      for (let index = 0; index < 8; index += 1) await Promise.resolve();
    });

    await ReactTestRenderer.act(async () => {
      renderer?.root.findByProps({testID: 'file-export'}).props.onPress();
    });
    expect(renderer?.root.findAllByProps({testID: 'file-export-confirm'}).length).toBeGreaterThan(0);
    await ReactTestRenderer.act(async () => {
      renderer?.root.findByProps({testID: 'file-export-cancel'}).props.onPress();
    });
    expect(exportGuestDirectory).not.toHaveBeenCalled();

    await ReactTestRenderer.act(async () => {
      renderer?.root.findByProps({testID: 'file-export'}).props.onPress();
    });
    await ReactTestRenderer.act(async () => {
      renderer?.root.findByProps({testID: 'file-export-run'}).props.onPress();
      for (let index = 0; index < 8; index += 1) await Promise.resolve();
    });
    expect(exportGuestDirectory).toHaveBeenCalledWith('home', ['projects']);
    expect(renderer?.root.findByProps({testID: 'file-export-result'}).props.children).toBe(
      'Copied 2 files (2 KB) to Download/Horus/projects-20260928-143205. 1 item was skipped (symlinks, special files, or unreadable files).',
    );

    await ReactTestRenderer.act(async () => {
      renderer?.unmount();
      await Promise.resolve();
    });
  });
});
