import React from 'react';
import ReactTestRenderer from 'react-test-renderer';
import {parseRemoteAccess, remoteAccessStatusLabel, type RemoteAccessResult} from '../src/remote/remoteAccess';
import {RemoteAccessPanel} from '../src/ui/RemoteAccessPanel';

const running = {
  status: 'success',
  enabled: true,
  state: 'running',
  detail: '',
  port: 8022,
  computers: [{fingerprint: 'SHA256:abc', label: 'laptop', managed: true}],
};

describe('remote access', () => {
  test('parses the native snapshot and rejects anything else', () => {
    const parsed = parseRemoteAccess(running);
    expect(parsed.kind).toBe('success');
    expect(parseRemoteAccess({...running, state: 'weird'}).kind).toBe('error');
    expect(parseRemoteAccess({...running, computers: [{label: 'x'}]}).kind).toBe('error');
    expect(parseRemoteAccess({status: 'error'}).kind).toBe('error');
    expect(parseRemoteAccess(null).kind).toBe('error');
  });

  test('describes each state plainly', () => {
    const base = (parseRemoteAccess(running) as Extract<RemoteAccessResult, {kind: 'success'}>).snapshot;
    expect(remoteAccessStatusLabel({...base, enabled: false})).toBe('Off. Nothing is listening.');
    expect(remoteAccessStatusLabel(base)).toContain('port 8022 on the phone only');
    expect(remoteAccessStatusLabel({...base, state: 'failed', detail: 'install_failed'})).toContain('Could not install');
  });

  test('panel toggles the switch and revokes a computer', async () => {
    const off = parseRemoteAccess({...running, enabled: false, state: 'stopped'});
    const on = parseRemoteAccess(running);
    const revoked = parseRemoteAccess({...running, computers: []});
    const read = jest.fn(async () => off);
    const setEnabled = jest.fn(async () => on);
    const revoke = jest.fn(async () => revoked);
    let renderer: ReactTestRenderer.ReactTestRenderer | undefined;
    await ReactTestRenderer.act(async () => {
      renderer = ReactTestRenderer.create(<RemoteAccessPanel read={read} revoke={revoke} setEnabled={setEnabled} />);
    });
    const status = () => renderer?.root.findByProps({testID: 'remote-access-status'}).props.children;
    expect(status()).toBe('Off. Nothing is listening.');

    await ReactTestRenderer.act(async () => {
      renderer?.root.findByProps({testID: 'remote-access-toggle'}).props.onPress();
      await Promise.resolve();
    });
    expect(setEnabled).toHaveBeenCalledWith(true);
    expect(status()).toContain('On.');

    await ReactTestRenderer.act(async () => {
      renderer?.root.findByProps({testID: 'remote-access-revoke'}).props.onPress();
      await Promise.resolve();
    });
    expect(revoke).toHaveBeenCalledWith('SHA256:abc');
    expect(renderer?.root.findAllByProps({testID: 'remote-access-computer'})).toHaveLength(0);
    await ReactTestRenderer.act(async () => { renderer?.unmount(); });
  });
});
