module.exports = {
  presets: ['module:@react-native/babel-preset'],
  plugins: ['@babel/plugin-transform-export-namespace-from'],
  overrides: [{
    test: /node_modules[/\\]@xterm[/\\]/,
    plugins: ['@babel/plugin-transform-class-static-block'],
  }],
};
