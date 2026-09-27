import NativeTerminalRuntime, {type Spec} from '../../native/NativeTerminalRuntime';
import {isValidTerminalSessionId} from './sessionContract';

type LaunchSessionRuntime = Pick<Spec, 'consumeLaunchSessionId'>;

/** Returns, once, the session a tapped Horus notification should open. */
export async function consumeLaunchSessionId(
  runtime: LaunchSessionRuntime | null = NativeTerminalRuntime,
): Promise<string | undefined> {
  if (runtime === null) return undefined;
  try {
    const sessionId = await runtime.consumeLaunchSessionId();
    return isValidTerminalSessionId(sessionId) ? sessionId : undefined;
  } catch {
    return undefined;
  }
}
