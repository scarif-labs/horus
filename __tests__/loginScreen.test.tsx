import React from 'react';
import ReactTestRenderer from 'react-test-renderer';
import {LoginScreen} from '../src/ui/LoginScreen';

describe('LoginScreen', () => {
  test('requires and forwards only the password', async () => {
    const logins: string[] = [];
    let renderer: ReactTestRenderer.ReactTestRenderer | undefined;
    await ReactTestRenderer.act(async () => {
      renderer = ReactTestRenderer.create(
        <LoginScreen
          onLogin={async password => { logins.push(password); }}
        />,
      );
    });

    expect(renderer?.root.findByProps({testID: 'login-logo'}).props.style).toMatchObject({height: 112, width: 112});
    expect(renderer?.root.findByProps({testID: 'login-wordmark'}).props.children).toBe('HORUS');
    expect(renderer?.root.findByProps({testID: 'login-password'}).props.placeholder).toBe('Password');
    expect(renderer?.root.findAll(node => typeof node.props.children === 'string' && ['Unlock', 'LOCKED', 'PASSWORD', 'UNLOCK WORKSPACE  →'].includes(node.props.children))).toHaveLength(0);

    await ReactTestRenderer.act(async () => {
      renderer?.root.findByProps({testID: 'login-submit'}).props.onPress();
    });
    expect(renderer?.root.findByProps({testID: 'login-validation-error'}).props.children).toBe('Enter your password to unlock Horus.');

    await ReactTestRenderer.act(async () => {
      renderer?.root.findByProps({testID: 'login-password'}).props.onChangeText('correct horse');
    });
    await ReactTestRenderer.act(async () => {
      renderer?.root.findByProps({testID: 'login-submit'}).props.onPress();
      await Promise.resolve();
    });
    expect(logins).toEqual(['correct horse']);
    await ReactTestRenderer.act(async () => { renderer?.unmount(); });
  });
});
