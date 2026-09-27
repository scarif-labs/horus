/**
 * @format
 */

import { applyRuntimePolyfills } from './src/runtimePolyfills';
import { AppRegistry } from 'react-native';
import App from './App';
import { name as appName } from './app.json';

applyRuntimePolyfills();

AppRegistry.registerComponent(appName, () => App);
