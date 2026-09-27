module.exports = {
  root: true,
  extends: '@react-native',
  overrides: [
    {
      // The horus CLI runs on the computer under plain Node, not React Native.
      files: ['cli/**/*.js'],
      env: {node: true, es2022: true},
    },
  ],
};
