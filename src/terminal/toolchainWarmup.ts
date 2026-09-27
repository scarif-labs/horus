import nativeTerminalRuntime, {type Spec, type TerminalToolchainTarget} from '../native/NativeTerminalRuntime';
import {provisionAlpineToolchain} from './runtimeStatus';

export const PREWARM_TOOLCHAINS: readonly TerminalToolchainTarget[] = [
  'shell',
  'github',
  'claude',
  'codex',
  'opencode',
] as const;

type ProvisionOutcome = Awaited<ReturnType<typeof provisionAlpineToolchain>>;

let requestSequence = 0;
const readyToolchains = new Set<TerminalToolchainTarget>();
const inFlightToolchains = new Map<TerminalToolchainTarget, Promise<ProvisionOutcome>>();

function nextRequestId(target: TerminalToolchainTarget): string {
  requestSequence = requestSequence >= Number.MAX_SAFE_INTEGER ? 1 : requestSequence + 1;
  return `horus-warmup-${target}-${requestSequence.toString(36)}`;
}

function rememberCompletion(target: TerminalToolchainTarget, task: Promise<ProvisionOutcome>): void {
  void task.then(result => {
    if (result.kind === 'success') readyToolchains.add(target);
    if (inFlightToolchains.get(target) === task) inFlightToolchains.delete(target);
  }, () => {
    if (inFlightToolchains.get(target) === task) inFlightToolchains.delete(target);
  });
}

function trackProvision(target: TerminalToolchainTarget, task: Promise<ProvisionOutcome>): Promise<ProvisionOutcome> {
  inFlightToolchains.set(target, task);
  rememberCompletion(target, task);
  return task;
}

/** Starts the bounded, sequential first-login warm-up without blocking the UI. */
export function prewarmAlpineToolchains(
  runtime: Pick<Spec, 'provisionToolchain'> | null = nativeTerminalRuntime,
): Promise<void> {
  let sequence = Promise.resolve();
  for (const target of PREWARM_TOOLCHAINS) {
    if (readyToolchains.has(target) || inFlightToolchains.has(target)) continue;
    const task = sequence.then(
      () => provisionAlpineToolchain(nextRequestId(target), target, runtime),
      () => provisionAlpineToolchain(nextRequestId(target), target, runtime),
    );
    trackProvision(target, task);
    sequence = task.then(() => undefined, () => undefined);
  }
  return sequence;
}

/** Reuses the login warm-up when present, otherwise provisions this target once. */
export async function ensureAlpineToolchainReady(
  requestId: string,
  target: TerminalToolchainTarget,
  runtime: Pick<Spec, 'provisionToolchain'> | null = nativeTerminalRuntime,
): Promise<ProvisionOutcome> {
  if (readyToolchains.has(target)) return {kind: 'success', requestId};
  const inFlight = inFlightToolchains.get(target);
  if (inFlight !== undefined) {
    const result = await inFlight;
    return result.kind === 'success' ? {kind: 'success', requestId} : result;
  }
  return trackProvision(target, provisionAlpineToolchain(requestId, target, runtime));
}
