import {formatSessionAge} from '../src/terminal/toolchainLabels';

describe('formatSessionAge', () => {
  const now = 1_700_000_000_000;
  const minute = 60_000;

  beforeEach(() => jest.spyOn(Date, 'now').mockReturnValue(now));
  afterEach(() => jest.restoreAllMocks());

  test.each([
    [0, 'just started'],
    [minute - 1, 'just started'],
    [minute, '1 min'],
    [2 * minute - 1, '1 min'],
    [59 * minute, '59 min'],
    [60 * minute, '1 hr'],
    [61 * minute, '1 hr 1 min'],
    [(3 * 60 + 25) * minute, '3 hr 25 min'],
  ])('formats an age of %d ms as %s', (ageMs, expected) => {
    expect(formatSessionAge(now - ageMs)).toBe(expected);
  });

  test('treats a start time in the future as just started', () => {
    expect(formatSessionAge(now + 5 * minute)).toBe('just started');
  });
});
