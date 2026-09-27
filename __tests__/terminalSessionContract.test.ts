import {
  buildResizeSessionRequest,
  buildSignalSessionRequest,
  buildStartSessionRequest,
  buildStopSessionRequest,
  buildSubscribeSessionEventsRequest,
  buildWriteSessionInputRequest,
  buildAcknowledgeSessionOutputRequest,
  decodeBase64,
  encodeBase64,
  isResizeSessionResponse,
  isSignalSessionResponse,
  isStartSessionResponse,
  isStopSessionResponse,
  isSubscribeSessionEventsResponse,
  isTerminalSessionEvent,
  isTerminalSessionExitEvent,
  isTerminalSessionOutputEvent,
  isWriteSessionInputResponse,
  isAcknowledgeSessionOutputResponse,
  isValidTerminalSessionColumns,
  isValidTerminalSessionId,
  isValidTerminalSessionRows,
  isValidTerminalSessionStopReason,
  TERMINAL_SESSION_EVENT_NAME,
  TERMINAL_SESSION_MAX_INPUT_BYTES,
} from '../src/terminal/session/sessionContract';
import {encodeTestBase64} from '../src/testSupport/base64';

describe('session id and bounds validation', () => {
  it('accepts native-shaped session ids only', () => {
    expect(isValidTerminalSessionId('s-1694169600-1')).toBe(true);
    expect(isValidTerminalSessionId('s-1')).toBe(true);
    expect(isValidTerminalSessionId('s')).toBe(false);
    expect(isValidTerminalSessionId('../escape')).toBe(false);
    expect(isValidTerminalSessionId('/absolute')).toBe(false);
    expect(isValidTerminalSessionId('UPPER')).toBe(false);
    expect(isValidTerminalSessionId('has space')).toBe(false);
    expect(isValidTerminalSessionId('x'.repeat(65))).toBe(false);
    expect(isValidTerminalSessionId('')).toBe(false);
    expect(isValidTerminalSessionId(42)).toBe(false);
  });

  it('bounds rows and columns to the mirrored native window', () => {
    expect(isValidTerminalSessionRows(2)).toBe(true);
    expect(isValidTerminalSessionRows(250)).toBe(true);
    expect(isValidTerminalSessionRows(1)).toBe(false);
    expect(isValidTerminalSessionRows(251)).toBe(false);
    expect(isValidTerminalSessionRows(24.5)).toBe(false);
    expect(isValidTerminalSessionColumns(2)).toBe(true);
    expect(isValidTerminalSessionColumns(500)).toBe(true);
    expect(isValidTerminalSessionColumns(501)).toBe(false);
  });

  it('accepts bounded machine-readable stop reasons only', () => {
    expect(isValidTerminalSessionStopReason('user_stop')).toBe(true);
    expect(isValidTerminalSessionStopReason('bridge_invalidated')).toBe(true);
    expect(isValidTerminalSessionStopReason('User Stop')).toBe(false);
    expect(isValidTerminalSessionStopReason('')).toBe(false);
    expect(isValidTerminalSessionStopReason('x'.repeat(65))).toBe(false);
  });
});

describe('base64 codec', () => {
  it('round-trips arbitrary byte values', () => {
    const bytes = new Uint8Array([0, 1, 2, 127, 128, 254, 255, 10, 13]);
    const encoded = encodeBase64(bytes);
    expect(encoded).toBe(encodeTestBase64(bytes));
    const decoded = decodeBase64(encoded);
    expect(decoded).not.toBeNull();
    expect(Array.from(decoded!)).toEqual(Array.from(bytes));
  });

  it('rejects non-canonical or malformed base64', () => {
    expect(decodeBase64('')).toBeNull();
    expect(decodeBase64('A')).toBeNull();
    expect(decodeBase64('AB')).toBeNull(); // missing padding
    expect(decodeBase64('ABC')).toBeNull();
    expect(decodeBase64('====')).toBeNull();
    expect(decodeBase64('A BC=')).toBeNull();
    expect(decodeBase64('QUJD=')).toBeNull(); // data after padding
    expect(decodeBase64('QUJ!')).toBeNull();
    expect(decodeBase64('AB==')).toBeNull(); // non-zero unused bits
    expect(decodeBase64('AAB=')).toBeNull(); // non-zero unused bits
  });

  it('decodes canonical padded values exactly', () => {
    expect(Array.from(decodeBase64('QQ==')!)).toEqual([65]);
    expect(Array.from(decodeBase64('QUI=')!)).toEqual([65, 66]);
    expect(Array.from(decodeBase64('QUJD')!)).toEqual([65, 66, 67]);
  });
});

describe('request builders', () => {
  it('builds a start request with defaults and rejects out-of-bounds sizes', () => {
    expect(buildStartSessionRequest('req-1')).toEqual({
      requestId: 'req-1',
      rows: 24,
      columns: 80,
    });
    expect(buildStartSessionRequest('req-2', {rows: 40, columns: 120})).toEqual({
      requestId: 'req-2',
      rows: 40,
      columns: 120,
    });
    expect(buildStartSessionRequest('req-2-toolchain', {toolchain: 'github'})).toEqual({
      requestId: 'req-2-toolchain',
      rows: 24,
      columns: 80,
      toolchain: 'github',
    });
    expect(buildStartSessionRequest('req-2-utility', {toolchain: 'shell', countsAgainstSessionLimit: false})).toEqual({
      requestId: 'req-2-utility',
      rows: 24,
      columns: 80,
      toolchain: 'shell',
      countsAgainstSessionLimit: false,
    });
    expect(buildStartSessionRequest('req-3', {rows: 1})).toBeNull();
    expect(buildStartSessionRequest('req-4', {columns: 501})).toBeNull();
    expect(buildStartSessionRequest('req-5', {toolchain: 'not-a-target' as never})).toBeNull();
    expect(buildStartSessionRequest('req-6', {countsAgainstSessionLimit: 'no' as never})).toBeNull();
    expect(buildStartSessionRequest('bad id!')).toBeNull();
  });

  it('builds a bounded write request carrying base64 bytes', () => {
    const bytes = new Uint8Array([104, 105, 10]);
    const request = buildWriteSessionInputRequest('req-1', 's-1-1', bytes);
    expect(request).toEqual({
      requestId: 'req-1',
      sessionId: 's-1-1',
      base64: encodeBase64(bytes),
    });
    expect(buildWriteSessionInputRequest('req-1', 's-1-1', new Uint8Array(0))).toBeNull();
    expect(
      buildWriteSessionInputRequest('req-1', 's-1-1', new Uint8Array(TERMINAL_SESSION_MAX_INPUT_BYTES + 1)),
    ).toBeNull();
    expect(buildWriteSessionInputRequest('req-1', '../bad', bytes)).toBeNull();
  });

  it('builds resize, signal, stop, and subscribe requests with validation', () => {
    expect(buildResizeSessionRequest('r-1', 's-1', 40, 120)).toEqual({
      requestId: 'r-1',
      sessionId: 's-1',
      rows: 40,
      columns: 120,
    });
    expect(buildResizeSessionRequest('r-1', 's-1', 0, 120)).toBeNull();
    expect(buildSignalSessionRequest('r-1', 's-1', 'sigint')).toEqual({
      requestId: 'r-1',
      sessionId: 's-1',
      signal: 'sigint',
    });
    expect(buildSignalSessionRequest('r-1', 's-1', 'sigstop' as 'sigint')).toBeNull();
    expect(buildStopSessionRequest('r-1', 's-1', 'user_stop')).toEqual({
      requestId: 'r-1',
      sessionId: 's-1',
      reason: 'user_stop',
    });
    expect(buildStopSessionRequest('r-1', 's-1', 'user stop')).toBeNull();
    expect(buildSubscribeSessionEventsRequest('r-1', 's-1')).toEqual({
      requestId: 'r-1',
      sessionId: 's-1',
    });
    expect(buildSubscribeSessionEventsRequest('r-1', 'nope!')).toBeNull();
    expect(buildAcknowledgeSessionOutputRequest('a-1', 's-1', 1)).toEqual({
      requestId: 'a-1',
      sessionId: 's-1',
      seq: 1,
    });
    expect(buildAcknowledgeSessionOutputRequest('a-1', 's-1', 0)).toBeNull();
  });
});

describe('wire response validators', () => {
  it('accepts a complete start success and rejects half-shaped ones', () => {
    const success = {
      requestId: 'r-1',
      status: 'success',
      sessionId: 's-1-1',
      pid: 4242,
      rows: 24,
      columns: 80,
    };
    expect(isStartSessionResponse(success, 'r-1')).toBe(true);
    expect(isStartSessionResponse({...success, extra: 1}, 'r-1')).toBe(false);
    expect(isStartSessionResponse({...success, pid: 0}, 'r-1')).toBe(false);
    expect(isStartSessionResponse({...success, rows: 999}, 'r-1')).toBe(false);
    expect(isStartSessionResponse({...success, requestId: 'other'}, 'r-1')).toBe(false);
    expect(isStartSessionResponse({...success, signal: undefined}, 'r-1')).toBe(false);
    expect(
      isStartSessionResponse(
        {requestId: 'r-1', status: 'error', errorCode: 'session_limit_reached'},
        'r-1',
      ),
    ).toBe(true);
    expect(
      isStartSessionResponse({requestId: 'r-1', status: 'error', errorCode: 'unknown_code'}, 'r-1'),
    ).toBe(false);
  });

  it('validates write and resize responses', () => {
    expect(
      isWriteSessionInputResponse({requestId: 'r-1', status: 'success', bytesWritten: 3}, 'r-1'),
    ).toBe(true);
    expect(
      isWriteSessionInputResponse({requestId: 'r-1', status: 'success', bytesWritten: 0}, 'r-1'),
    ).toBe(false);
    expect(
      isWriteSessionInputResponse({requestId: 'r-1', status: 'success'}, 'r-1'),
    ).toBe(false);
    expect(
      isWriteSessionInputResponse(
        {requestId: 'r-1', status: 'success', bytesWritten: 3, extra: undefined},
        'r-1',
      ),
    ).toBe(false);
    expect(
      isResizeSessionResponse(
        {requestId: 'r-1', status: 'success', rows: 40, columns: 120},
        'r-1',
      ),
    ).toBe(true);
    expect(
      isResizeSessionResponse({requestId: 'r-1', status: 'success', rows: 40}, 'r-1'),
    ).toBe(false);
    expect(
      isSignalSessionResponse({requestId: 'r-1', status: 'success', signal: 'sigkill'}, 'r-1'),
    ).toBe(true);
    expect(
      isSignalSessionResponse({requestId: 'r-1', status: 'success', signal: 'nope'}, 'r-1'),
    ).toBe(false);
  });

  it('validates stop teardown observations including optional exit fields', () => {
    expect(
      isStopSessionResponse(
        {
          requestId: 'r-1',
          status: 'success',
          sessionId: 's-1',
          exitReason: 'user_stop',
          remainingProcessCount: 0,
          stoppedWithinDeadline: true,
        },
        'r-1',
      ),
    ).toBe(true);
    expect(
      isStopSessionResponse(
        {
          requestId: 'r-1',
          status: 'success',
          sessionId: 's-1',
          exitCode: 130,
          signal: 'sigint',
          exitReason: 'user_stop',
          remainingProcessCount: 0,
          stoppedWithinDeadline: true,
        },
        'r-1',
      ),
    ).toBe(true);
    expect(
      isStopSessionResponse(
        {
          requestId: 'r-1',
          status: 'success',
          sessionId: 's-1',
          exitCode: 300,
          exitReason: 'user_stop',
          remainingProcessCount: 0,
          stoppedWithinDeadline: true,
        },
        'r-1',
      ),
    ).toBe(false);
    expect(
      isStopSessionResponse(
        {
          requestId: 'r-1',
          status: 'success',
          sessionId: 's-1',
          exitReason: 'user_stop',
          remainingProcessCount: -1,
          stoppedWithinDeadline: true,
        },
        'r-1',
      ),
    ).toBe(false);
  });

  it('validates subscribe responses carrying the current session state', () => {
    expect(
      isSubscribeSessionEventsResponse(
        {
          requestId: 'r-1',
          status: 'success',
          sessionId: 's-1',
          eventName: TERMINAL_SESSION_EVENT_NAME,
          sessionState: 'running',
          firstAvailableSeq: 1,
          lastEmittedSeq: 0,
          replayAvailable: true,
        },
        'r-1',
      ),
    ).toBe(true);
    expect(
      isSubscribeSessionEventsResponse(
        {
          requestId: 'r-1',
          status: 'success',
          sessionId: 's-1',
          eventName: 'other-event',
          sessionState: 'running',
        },
        'r-1',
      ),
    ).toBe(false);
    expect(
      isSubscribeSessionEventsResponse(
        {
          requestId: 'r-1',
          status: 'success',
          sessionId: 's-1',
          eventName: TERMINAL_SESSION_EVENT_NAME,
          sessionState: 'exited',
          firstAvailableSeq: 1,
          lastEmittedSeq: 1,
          replayAvailable: true,
          exitCode: 0,
          exitReason: 'process_exit',
        },
        'r-1',
      ),
    ).toBe(true);
    expect(
      isSubscribeSessionEventsResponse(
        {
          requestId: 'r-1',
          status: 'success',
          sessionId: 's-1',
          eventName: TERMINAL_SESSION_EVENT_NAME,
          sessionState: 'running',
          firstAvailableSeq: 3,
          lastEmittedSeq: 3,
          replayAvailable: false,
        },
        'r-1',
      ),
    ).toBe(true);
  });

  it('validates output acknowledgements and exact sequence correlation', () => {
    expect(
      isAcknowledgeSessionOutputResponse(
        {
          requestId: 'a-1',
          status: 'success',
          sessionId: 's-1',
          acknowledgedSeq: 4,
          outstandingChunks: 2,
        },
        'a-1',
        's-1',
        4,
      ),
    ).toBe(true);
    expect(
      isAcknowledgeSessionOutputResponse(
        {
          requestId: 'a-1',
          status: 'success',
          sessionId: 's-1',
          acknowledgedSeq: 3,
          outstandingChunks: 2,
        },
        'a-1',
        's-1',
        4,
      ),
    ).toBe(false);
    expect(
      isAcknowledgeSessionOutputResponse(
        {
          requestId: 'a-1',
          status: 'success',
          sessionId: 's-1',
          acknowledgedSeq: 4,
          outstandingChunks: 33,
        },
        'a-1',
      ),
    ).toBe(false);
  });
});

describe('session event validators', () => {
  it('accepts only complete ordered output events', () => {
    const event = {type: 'output', sessionId: 's-1', seq: 3, base64: 'aGk='};
    expect(isTerminalSessionOutputEvent(event)).toBe(true);
    expect(isTerminalSessionEvent(event)).toBe(true);
    expect(isTerminalSessionOutputEvent({...event, seq: 0})).toBe(false);
    expect(isTerminalSessionOutputEvent({...event, seq: 1.5})).toBe(false);
    expect(isTerminalSessionOutputEvent({type: 'output', sessionId: 's-1', seq: 3})).toBe(false);
    expect(isTerminalSessionOutputEvent({...event, base64: '!!!'})).toBe(false);
    expect(
      isTerminalSessionOutputEvent({...event, base64: 'A'.repeat(32_768)}),
    ).toBe(false);
    expect(
      isTerminalSessionOutputEvent({
        type: 'output',
        sessionId: 's-1',
        seq: 1,
        base64: encodeBase64(new Uint8Array(8 * 1024 + 1)),
      }),
    ).toBe(false);
  });

  it('accepts only bounded exit events', () => {
    const event = {type: 'exit', sessionId: 's-1', reason: 'process_exit', exitCode: 0};
    expect(isTerminalSessionExitEvent(event)).toBe(true);
    expect(isTerminalSessionExitEvent({...event, exitCode: 256})).toBe(false);
    expect(isTerminalSessionExitEvent({type: 'exit', sessionId: 's-1'})).toBe(false);
    expect(
      isTerminalSessionExitEvent({type: 'exit', sessionId: 's-1', reason: 'killed', signal: 'sigkill'}),
    ).toBe(true);
  });
});
