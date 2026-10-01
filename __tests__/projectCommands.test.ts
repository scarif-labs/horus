import {
  buildProjectCloneCommand,
  harnessSessionTarget,
  printHiddenMarker,
  workspaceProjectDirectory,
} from '../src/projects/projectCommands';

// Everything after the shared normalize_git_remote() helper.
function cloneScriptBody(command: string): string[] {
  const lines = command.split('\n');
  return lines.slice(lines.indexOf("mkdir -p '/workspace/projects'"));
}

describe('workspaceProjectDirectory', () => {
  it('maps safe names into the projects folder', () => {
    expect(workspaceProjectDirectory('hello')).toBe('/workspace/projects/hello');
    expect(workspaceProjectDirectory('My.Repo_2-x')).toBe('/workspace/projects/My.Repo_2-x');
    expect(workspaceProjectDirectory('...')).toBe('/workspace/projects/...');
    expect(workspaceProjectDirectory('a'.repeat(48))).toBe(`/workspace/projects/${'a'.repeat(48)}`);
  });

  it('rejects dot entries, overlong names, slashes and other characters', () => {
    for (const name of ['', '.', '..', 'a'.repeat(49), 'a/b', '../x', '/abs', 'a\\b', 'has space', "it's", 'semi;colon']) {
      expect(workspaceProjectDirectory(name)).toBeUndefined();
    }
  });
});

describe('printHiddenMarker', () => {
  it('prints the quoted marker on its own line and erases it', () => {
    expect(printHiddenMarker('M1')).toBe(String.raw`printf '\n%s\n\033[1A\033[2K' 'M1'`);
  });
});

describe('buildProjectCloneCommand', () => {
  it('clones a GitHub repository with gh and accepts an existing matching checkout', () => {
    const command = buildProjectCloneCommand('octo/hello', '/workspace/projects/hello', 'M1', 'github');
    expect(command.startsWith('set -e\nnormalize_git_remote() {\n')).toBe(true);
    expect(cloneScriptBody(command)).toEqual([
      "mkdir -p '/workspace/projects'",
      "if [ -e '/workspace/projects/hello' ] || [ -L '/workspace/projects/hello' ]; then",
      "  destination_root=$(cd '/workspace/projects/hello' 2>/dev/null && pwd -P || true)",
      "  existing_root=$(git -C '/workspace/projects/hello' rev-parse --show-toplevel 2>/dev/null || true)",
      "  existing_origin=$(git -C '/workspace/projects/hello' remote get-url origin 2>/dev/null || true)",
      `  if [ ! -L '/workspace/projects/hello' ] && [ -n "$destination_root" ] && [ "$existing_root" = "$destination_root" ] && [ -n "$existing_origin" ] && [ "$(normalize_git_remote "$existing_origin" 2>/dev/null || true)" = "$(normalize_git_remote 'https://github.com/octo/hello.git')" ]; then`,
      String.raw`    printf '%s\n' 'Using the existing checkout of this repository.'`,
      String.raw`    printf '\n%s\n\033[1A\033[2K' 'M1'`,
      '    exit 0',
      '  fi',
      String.raw`  printf '%s\n' 'That workspace folder already exists but is not a checkout of this repository. It was left unchanged.' >&2`,
      '  exit 17',
      'fi',
      "gh repo clone 'octo/hello' '/workspace/projects/hello'",
      String.raw`printf '\n%s\n\033[1A\033[2K' 'M1'`,
    ]);
  });

  it('clones a URL with git and shell-quotes the source and directory', () => {
    const command = buildProjectCloneCommand("https://example.com/it's.git", "/workspace/projects/it's", 'M2', 'url');
    const body = cloneScriptBody(command);
    expect(body[1]).toBe(String.raw`if [ -e '/workspace/projects/it'\''s' ] || [ -L '/workspace/projects/it'\''s' ]; then`);
    expect(body[5]).toContain(String.raw`"$(normalize_git_remote 'https://example.com/it'\''s.git')"`);
    expect(body.slice(-2)).toEqual([
      String.raw`git clone -- 'https://example.com/it'\''s.git' '/workspace/projects/it'\''s'`,
      String.raw`printf '\n%s\n\033[1A\033[2K' 'M2'`,
    ]);
  });

  it('prints the hidden marker on both the reuse and the fresh-clone path', () => {
    for (const kind of ['github', 'url'] as const) {
      const lines = buildProjectCloneCommand('octo/hello', '/workspace/projects/hello', 'MARK', kind).split('\n');
      const markerLines = lines.flatMap((line, index) => (line.includes("'MARK'") ? [index] : []));
      expect(markerLines).toHaveLength(2);
      expect(lines[markerLines[0] + 1]).toBe('    exit 0');
      expect(markerLines[1]).toBe(lines.length - 1);
    }
  });
});

describe('harnessSessionTarget', () => {
  const directory = '/workspace/projects/demo';
  const preamble = String.raw`stty echo 2>/dev/null || true; if ! command -v zsh >/dev/null 2>&1; then printf '%s\n' 'zsh is not available yet; close and reopen this terminal to retry' >&2; exit 127; fi;`;

  it('starts Claude with its messaging socket in an interactive login zsh', () => {
    expect(harnessSessionTarget({title: 'Claude Code', eyebrow: 'HARNESS', toolchain: 'claude'}, 'demo', directory)).toEqual({
      title: 'Claude Code',
      eyebrow: 'demo',
      command: `${preamble} exec zsh -lic 'mkdir -p '\\''/workspace/projects/demo'\\'' && cd '\\''/workspace/projects/demo'\\'' && exec claude --messaging-socket-path "/tmp/claude-messaging-$(id -u)/$$.sock"'`,
      toolchain: 'claude',
      returnTo: 'home',
    });
  });

  it('starts Codex in an interactive login zsh', () => {
    expect(harnessSessionTarget({title: 'Codex', eyebrow: 'HARNESS', toolchain: 'codex'}, 'demo', directory)).toEqual({
      title: 'Codex',
      eyebrow: 'demo',
      command: `${preamble} exec zsh -lic 'mkdir -p '\\''/workspace/projects/demo'\\'' && cd '\\''/workspace/projects/demo'\\'' && exec codex'`,
      toolchain: 'codex',
      returnTo: 'home',
    });
  });

  it('starts OpenCode through the script zsh without startup files', () => {
    expect(harnessSessionTarget({title: 'OpenCode', eyebrow: 'HARNESS', toolchain: 'opencode'}, 'demo', directory)).toEqual({
      title: 'OpenCode',
      eyebrow: 'demo',
      command: `${preamble} exec zsh -fc 'mkdir -p '\\''/workspace/projects/demo'\\'' && cd '\\''/workspace/projects/demo'\\'' && exec opencode'`,
      toolchain: 'opencode',
      returnTo: 'home',
    });
  });
});
