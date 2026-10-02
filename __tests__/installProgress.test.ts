import {installStepForStage, lastInstallStage} from '../src/terminal/InstallProgressOverlay';

describe('install progress stages', () => {
  test('maps provisioning stages onto the four visible steps', () => {
    expect(installStepForStage('start', 'codex')).toBe(0);
    expect(installStepForStage('apk', 'codex')).toBe(1);
    expect(installStepForStage('github', 'codex')).toBe(1);
    expect(installStepForStage('github', 'github')).toBe(2);
    for (const stage of ['base_ready', 'claude', 'codex', 'opencode', 'normalize', 'verify']) {
      expect(installStepForStage(stage, 'claude')).toBe(2);
    }
    expect(installStepForStage('ready', 'opencode')).toBe(3);
  });

  test('reads the last stage marker in a chunk', () => {
    expect(lastInstallStage('(1/7) Installing ada-libs\n')).toBeUndefined();
    expect(lastInstallStage('HORUS_INSTALL_STAGE=start\nHORUS_INSTALL_STAGE=apk\n')).toBe('apk');
    expect(lastInstallStage('noise HORUS_INSTALL_STAGE=opencode_failed\n')).toBe('opencode_failed');
  });
});
