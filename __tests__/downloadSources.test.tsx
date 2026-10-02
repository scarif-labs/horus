import React from 'react';
import ReactTestRenderer from 'react-test-renderer';
import {PINNED_ROOTFS_URL} from '../src/terminal/distroContract';
import {
  normalizeMirrorUrl,
  npmRegistryFor,
  readDownloadSources,
  rootfsUrl,
  writeDownloadSources,
  type DownloadSources,
  type DownloadSourcesResult,
} from '../src/terminal/downloadSources';
import {DownloadSourcesPanel} from '../src/ui/DownloadSourcesPanel';

describe('download sources', () => {
  test('accepts only plain https mirror addresses', () => {
    expect(normalizeMirrorUrl(' https://mirrors.ustc.edu.cn/alpine/ ')).toBe('https://mirrors.ustc.edu.cn/alpine');
    for (const bad of ['http://mirrors.ustc.edu.cn/alpine', 'https://u@mirror.example', 'https://mirror.example/?a=1', 'https://mirror.example/a b', 'https://mirror.example/$(x)']) {
      expect(normalizeMirrorUrl(bad)).toBeUndefined();
    }
  });

  test('finds the pinned archive on a mirror', () => {
    expect(rootfsUrl(undefined)).toBe(PINNED_ROOTFS_URL);
    expect(rootfsUrl('https://mirrors.aliyun.com/alpine')).toBe('https://mirrors.aliyun.com/alpine/v3.24/releases/aarch64/alpine-minirootfs-3.24.0-aarch64.tar.gz');
  });

  test('pairs China Alpine mirrors with the China npm registry', () => {
    expect(npmRegistryFor('https://mirrors.ustc.edu.cn/alpine')).toBe('https://registry.npmmirror.com');
    expect(npmRegistryFor(undefined)).toBeUndefined();
    expect(npmRegistryFor('https://mirror.example/alpine')).toBeUndefined();
  });

  test('reads and writes through the native module and rejects bad responses', async () => {
    const runtime = {
      getDownloadSources: jest.fn(async () => ({status: 'success' as const, alpineMirror: 'https://mirrors.ustc.edu.cn/alpine'})),
      setDownloadSources: jest.fn(async (request: {alpineMirror?: string; npmRegistry?: string}) => ({status: 'success' as const, ...request})),
    };
    await expect(readDownloadSources(runtime)).resolves.toEqual({kind: 'success', sources: {alpineMirror: 'https://mirrors.ustc.edu.cn/alpine', npmRegistry: undefined}});
    await expect(writeDownloadSources({npmRegistry: 'https://registry.npmmirror.com/'}, runtime)).resolves.toEqual({kind: 'success', sources: {alpineMirror: undefined, npmRegistry: 'https://registry.npmmirror.com'}});
    expect(runtime.setDownloadSources).toHaveBeenLastCalledWith({npmRegistry: 'https://registry.npmmirror.com'});
    await expect(writeDownloadSources({alpineMirror: 'http://insecure.example'}, runtime)).resolves.toEqual({kind: 'error', errorCode: 'invalid_request'});
    runtime.getDownloadSources.mockResolvedValueOnce({status: 'success', alpineMirror: 'ftp://mirror.example/alpine'});
    await expect(readDownloadSources(runtime)).resolves.toEqual({kind: 'error', errorCode: 'invalid_response'});
  });
});

describe('DownloadSourcesPanel', () => {
  test('shows the current sources and saves a chosen mirror', async () => {
    let stored: DownloadSources = {};
    const read = jest.fn(async (): Promise<DownloadSourcesResult> => ({kind: 'success', sources: stored}));
    const write = jest.fn(async (sources: DownloadSources): Promise<DownloadSourcesResult> => {
      stored = sources;
      return {kind: 'success', sources};
    });
    let renderer!: ReactTestRenderer.ReactTestRenderer;
    await ReactTestRenderer.act(async () => {
      renderer = ReactTestRenderer.create(<DownloadSourcesPanel read={read} write={write} />);
    });
    const press = async (testID: string) => {
      await ReactTestRenderer.act(async () => {
        renderer.root.findByProps({testID}).props.onPress();
        await Promise.resolve();
      });
    };
    const detail = () => renderer.root.findAll(node => node.props.children === 'Alpine CDN · Worldwide' || node.props.children === 'USTC · China');
    expect(detail().length).toBeGreaterThan(0);

    await press('settings-alpineMirror-row');
    await press('settings-alpineMirror-ustc');
    expect(write).toHaveBeenCalledWith({alpineMirror: 'https://mirrors.ustc.edu.cn/alpine'});
    expect(renderer.root.findAll(node => node.props.children === 'USTC · China').length).toBeGreaterThan(0);

    await press('settings-alpineMirror-custom');
    await ReactTestRenderer.act(async () => {
      renderer.root.findByProps({testID: 'settings-alpineMirror-custom-url'}).props.onChangeText('http://nope.example');
    });
    await press('settings-alpineMirror-custom-use');
    expect(renderer.root.findAllByProps({testID: 'settings-alpineMirror-custom-error'}).length).toBeGreaterThan(0);
    expect(write).toHaveBeenCalledTimes(1);
    await ReactTestRenderer.act(async () => { renderer.unmount(); });
  });
});
