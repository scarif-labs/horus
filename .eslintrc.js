module.exports = {
  root: true,
  extends: '@react-native',
  rules: {
    // `void promise;` marks a deliberate fire-and-forget call.
    'no-void': ['warn', {allowAsStatement: true}],
  },
  overrides: [
    {
      // The horus CLI runs on the computer under plain Node, not React Native.
      files: ['cli/**/*.js'],
      env: {node: true, es2022: true},
    },
    {
      // Terminal and shell-output code decodes bytes and strips escape sequences.
      files: [
        'src/terminal/**',
        'src/files/fileExplorer.ts',
        'src/projects/githubRepositories.ts',
      ],
      rules: {
        'no-bitwise': 'off',
        'no-control-regex': 'off',
      },
    },
  ],
};
