export type RequestIdFactory = ((name: string) => string) & Readonly<{
  /** The sequence used by the most recent ID, or the initial value before any. */
  currentSequence: () => number;
}>;

/**
 * Returns a generator of `${prefix}-${name}-${sequence}` request IDs with its
 * own counter. The sequence is base 36 and wraps back to 1 after
 * Number.MAX_SAFE_INTEGER. `initialSequence` exists for tests.
 */
export function createRequestIdFactory(prefix: string, initialSequence = 0): RequestIdFactory {
  let sequence = initialSequence;
  const nextId = (name: string): string => {
    sequence = sequence >= Number.MAX_SAFE_INTEGER ? 1 : sequence + 1;
    return `${prefix}-${name}-${sequence.toString(36)}`;
  };
  return Object.assign(nextId, {currentSequence: () => sequence});
}
