import {createRequestIdFactory} from '../src/terminal/requestIds';

describe('createRequestIdFactory', () => {
  test('formats prefix, name and a base-36 sequence', () => {
    const nextId = createRequestIdFactory('files');
    expect(nextId('list')).toBe('files-list-1');
    expect(nextId('read')).toBe('files-read-2');
    for (let index = 3; index < 36; index += 1) nextId('list');
    expect(nextId('list')).toBe('files-list-10');
  });

  test('keeps an independent counter per factory', () => {
    const terminalId = createRequestIdFactory('terminal');
    const launcherId = createRequestIdFactory('launcher');
    expect(terminalId('start')).toBe('terminal-start-1');
    expect(terminalId('attach')).toBe('terminal-attach-2');
    expect(launcherId('recents')).toBe('launcher-recents-1');
  });

  test('reports the sequence of the most recent ID', () => {
    const nextId = createRequestIdFactory('files');
    expect(nextId.currentSequence()).toBe(0);
    nextId('list');
    nextId('list');
    expect(nextId.currentSequence()).toBe(2);
  });

  test('wraps to 1 after Number.MAX_SAFE_INTEGER', () => {
    const nextId = createRequestIdFactory('github', Number.MAX_SAFE_INTEGER - 1);
    expect(nextId('repos')).toBe(`github-repos-${Number.MAX_SAFE_INTEGER.toString(36)}`);
    expect(nextId('repos')).toBe('github-repos-1');
    expect(nextId('repos')).toBe('github-repos-2');
  });
});
