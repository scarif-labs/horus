import {provisionAlpineToolchain} from '../src/terminal/runtimeStatus';
import {saveUserProfile} from '../src/profile/profileStore';
import {setupOnboardingProfile} from '../src/profile/onboardingSetup';

jest.mock('../src/terminal/runtimeStatus', () => ({
  provisionAlpineToolchain: jest.fn(),
}));

jest.mock('../src/profile/profileStore', () => ({
  saveUserProfile: jest.fn(),
}));

describe('setupOnboardingProfile', () => {
  const provision = jest.mocked(provisionAlpineToolchain);
  const save = jest.mocked(saveUserProfile);

  beforeEach(() => {
    provision.mockReset();
    save.mockReset();
  });

  test('prepares the Alpine shell before saving the profile', async () => {
    const order: string[] = [];
    provision.mockImplementation(async (requestId, target) => {
      order.push(`provision:${target}:${requestId}`);
      return {kind: 'success', requestId};
    });
    save.mockImplementation(async () => {
      order.push('save-profile');
      return true;
    });

    const result = await setupOnboardingProfile('test-password', 'setup-shell-1');

    expect(result).toEqual({kind: 'success'});
    expect(order).toEqual(['provision:shell:setup-shell-1', 'save-profile']);
  });

  test('does not complete onboarding when Alpine package setup fails', async () => {
    provision.mockResolvedValue({
      kind: 'error',
      requestId: 'setup-shell-2',
      errorCode: 'internal_error',
    });

    const result = await setupOnboardingProfile('test-password', 'setup-shell-2');

    expect(result).toEqual({kind: 'error', stage: 'alpine-tools'});
    expect(save).not.toHaveBeenCalled();
  });

  test('reports profile persistence failure after tools are ready', async () => {
    provision.mockResolvedValue({kind: 'success', requestId: 'setup-shell-3'});
    save.mockResolvedValue(false);

    const result = await setupOnboardingProfile('test-password', 'setup-shell-3');

    expect(result).toEqual({kind: 'error', stage: 'profile'});
  });
});
