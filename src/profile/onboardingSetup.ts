import {provisionAlpineToolchain} from '../terminal/runtimeStatus';
import {saveUserProfile} from './profileStore';

export type OnboardingSetupOutcome =
  | Readonly<{kind: 'success'}>
  | Readonly<{kind: 'error'; stage: 'alpine-tools' | 'profile'}>;

/** Installs the profile shell's Alpine tools before onboarding is complete. */
export async function setupOnboardingProfile(
  password: string,
  requestId: string,
  onToolsReady: () => void = () => undefined,
): Promise<OnboardingSetupOutcome> {
  const tools = await provisionAlpineToolchain(requestId, 'shell');
  if (tools.kind !== 'success') return {kind: 'error', stage: 'alpine-tools'};
  onToolsReady();
  if (!(await saveUserProfile(password))) return {kind: 'error', stage: 'profile'};
  return {kind: 'success'};
}
