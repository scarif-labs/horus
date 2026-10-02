import {pasteSequence, readTerminalPaste, type TerminalPasteRuntime} from '../src/terminal/terminalPaste';

function pasteRuntime(text: string | null, bracketed = false): TerminalPasteRuntime & {isBracketedPaste: jest.Mock} {
  return {
    readClipboardText: jest.fn(async () => text),
    isBracketedPaste: jest.fn(async () => bracketed),
  };
}

describe('terminal paste', () => {
  test('sends newlines as carriage returns', () => {
    expect(pasteSequence('one\ntwo\r\nthree\rfour', false)).toBe('one\rtwo\rthree\rfour');
  });

  test('wraps bracketed pastes and drops ESC so the text cannot close the bracket', () => {
    expect(pasteSequence('code', true)).toBe('\u001b[200~code\u001b[201~');
    expect(pasteSequence('a\u001b[201~rm -rf\n', true)).toBe('\u001b[200~a[201~rm -rf\r\u001b[201~');
  });

  test('asks a native session whether bracketed paste is on', async () => {
    const runtime = pasteRuntime('token-123', true);
    await expect(readTerminalPaste('s-1', false, runtime)).resolves.toBe('\u001b[200~token-123\u001b[201~');
    expect(runtime.isBracketedPaste).toHaveBeenCalledWith('s-1');
  });

  test('uses the local mode for other sessions', async () => {
    const runtime = pasteRuntime('ls');
    await expect(readTerminalPaste(undefined, true, runtime)).resolves.toBe('\u001b[200~ls\u001b[201~');
    await expect(readTerminalPaste(undefined, false, runtime)).resolves.toBe('ls');
    expect(runtime.isBracketedPaste).not.toHaveBeenCalled();
  });

  test('sends nothing for an empty clipboard or a missing runtime', async () => {
    await expect(readTerminalPaste('s-1', false, pasteRuntime(null))).resolves.toBeUndefined();
    await expect(readTerminalPaste('s-1', false, pasteRuntime(''))).resolves.toBeUndefined();
    await expect(readTerminalPaste('s-1', false, null)).resolves.toBeUndefined();
  });
});
