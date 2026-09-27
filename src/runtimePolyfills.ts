/**
 * Explicit Hermes compatibility shims used by the Alpine terminal runtime.
 * These imports are side-effect-only and stay at the app boundary so runtime
 * setup remains visible and deterministic.
 */
import 'fast-text-encoding';
import 'react-native-get-random-values';
import 'web-streams-polyfill/polyfill';

type RuntimeNavigator = {
  userAgent?: unknown;
  platform?: unknown;
  [key: string]: unknown;
};

type RuntimeGlobal = typeof globalThis & {
  navigator?: RuntimeNavigator;
};

function ensureNavigator(): void {
  const runtimeGlobal = globalThis as unknown as RuntimeGlobal;
  const current = runtimeGlobal.navigator;
  if (typeof current?.userAgent === 'string' && typeof current.platform === 'string') return;

  try {
    Object.defineProperty(runtimeGlobal, 'navigator', {
      configurable: true,
      enumerable: false,
      value: {
        ...(current !== null && typeof current === 'object' ? current : {}),
        platform: typeof current?.platform === 'string' ? current.platform : 'ReactNative',
        userAgent: typeof current?.userAgent === 'string' ? current.userAgent : 'ReactNative',
      },
      writable: true,
    });
  } catch {
    // A host-provided navigator may be non-configurable. Try to complete it
    // in place; xterm only requires these two string fields.
    if (current === undefined) return;
    try {
      if (typeof current.userAgent !== 'string') current.userAgent = 'ReactNative';
      if (typeof current.platform !== 'string') current.platform = 'ReactNative';
    } catch {
      // Leave an immutable host navigator untouched.
    }
  }
}

// xterm/headless reads navigator at module evaluation time. This top-level
// setup runs before the app module imports the terminal renderer.
ensureNavigator();

type Cloneable = unknown;

function cloneValue<T extends Cloneable>(value: T, seen = new WeakMap<object, unknown>()): T {
  if (value === null || typeof value !== 'object') return value;
  if (seen.has(value)) return seen.get(value) as T;

  if (value instanceof Date) return new Date(value.getTime()) as T;
  if (value instanceof RegExp) return new RegExp(value.source, value.flags) as T;
  if (value instanceof ArrayBuffer) return value.slice(0) as T;
  if (ArrayBuffer.isView(value)) {
    return new (value.constructor as {new (buffer: ArrayBuffer): unknown})(
      value.buffer.slice(0) as ArrayBuffer,
    ) as T;
  }

  if (value instanceof Map) {
    const result = new Map();
    seen.set(value, result);
    value.forEach((entry, key) => result.set(cloneValue(key, seen), cloneValue(entry, seen)));
    return result as T;
  }
  if (value instanceof Set) {
    const result = new Set();
    seen.set(value, result);
    value.forEach(entry => result.add(cloneValue(entry, seen)));
    return result as T;
  }

  const result = Array.isArray(value) ? [] : {};
  seen.set(value, result);
  for (const [key, entry] of Object.entries(value)) {
    (result as Record<string, unknown>)[key] = cloneValue(entry, seen);
  }
  return result as T;
}

export function applyRuntimePolyfills(): void {
  ensureNavigator();
  if (typeof globalThis.structuredClone !== 'function') {
    globalThis.structuredClone = <T>(value: T) => cloneValue(value);
  }
}
