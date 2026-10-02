import nativeTerminalRuntime, {type Spec} from '../native/NativeTerminalRuntime';

export type TerminalPasteRuntime = Pick<Spec, 'readClipboardText' | 'isBracketedPaste'>;

const BRACKETED_PASTE_START = '\u001b[200~';
const BRACKETED_PASTE_END = '\u001b[201~';

/**
 * The bytes to send for pasted [text], as a terminal would: newlines become
 * carriage returns, and the text is wrapped in bracketed-paste markers when
 * the app asked for them. ESC is dropped so pasted text cannot end the
 * bracket early or smuggle in a key sequence.
 */
export function pasteSequence(text: string, bracketed: boolean): string {
  const body = text.replace(/\r\n?|\n/g, '\r').replace(/\u001b/g, '');
  return bracketed ? `${BRACKETED_PASTE_START}${body}${BRACKETED_PASTE_END}` : body;
}

/**
 * Reads the clipboard and returns what to send to the session, or undefined
 * when there is nothing to paste. A native session's engine knows whether
 * bracketed paste is on; for other sessions the caller passes it in.
 */
export async function readTerminalPaste(
  nativeSessionId: string | undefined,
  localBracketed: boolean,
  runtime: TerminalPasteRuntime | null = nativeTerminalRuntime,
): Promise<string | undefined> {
  if (runtime === null) return undefined;
  const text = await runtime.readClipboardText().catch(() => null);
  if (text === null || text.length === 0) return undefined;
  const bracketed = nativeSessionId === undefined
    ? localBracketed
    : await runtime.isBracketedPaste(nativeSessionId).catch(() => false);
  const sequence = pasteSequence(text, bracketed);
  return sequence.length > 0 ? sequence : undefined;
}
