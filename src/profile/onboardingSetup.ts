import {provisionAlpineToolchain} from '../terminal/runtimeStatus';
import {saveUserProfile} from './profileStore';

export type OnboardingSetupOutcome =
  | Readonly<{kind: 'success'}>
  | Readonly<{kind: 'error'; stage: 'alpine-tools' | 'profile'}>;

export type OnboardingSetupOptions = Readonly<{
  onToolsReady?: () => void;
  /**
   * Saves the profile without the shell's Alpine tools, e.g. offline after
   * importing the rootfs. The first terminal installs them instead.
   */
  skipTools?: boolean;
}>;

/** Installs the profile shell's Alpine tools before onboarding is complete. */
export async function setupOnboardingProfile(
  password: string,
  requestId: string,
  {onToolsReady = () => undefined, skipTools = false}: OnboardingSetupOptions = {},
): Promise<OnboardingSetupOutcome> {
  if (!skipTools) {
    const tools = await provisionAlpineToolchain(requestId, 'shell');
    if (tools.kind !== 'success') return {kind: 'error', stage: 'alpine-tools'};
  }
  onToolsReady();
  if (!(await saveUserProfile(password))) return {kind: 'error', stage: 'profile'};
  return {kind: 'success'};
}
