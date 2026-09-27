import React from 'react';
import ReactTestRenderer from 'react-test-renderer';
import {Text} from 'react-native';
import {GithubAccountScreen} from '../src/ui/GithubAccountScreen';

describe('GithubAccountScreen', () => {
  test('shows account identity and profile link, and provides back and logout actions', async () => {
    const onBack = jest.fn();
    const onLogout = jest.fn();
    let renderer: ReactTestRenderer.ReactTestRenderer | undefined;
    await ReactTestRenderer.act(async () => {
      renderer = ReactTestRenderer.create(
        <GithubAccountScreen
          account={{username: 'octocat', avatarUrl: 'https://avatars.githubusercontent.com/u/1'}}
          onBack={onBack}
          onLogout={onLogout}
        />,
      );
    });

    expect(renderer?.root.findByProps({testID: 'github-account-avatar'}).props.source).toEqual({uri: 'https://avatars.githubusercontent.com/u/1'});
    expect(renderer?.root.findByProps({testID: 'github-account-username'}).props.children).toEqual(['@', 'octocat']);
    const profileLink = renderer?.root.findByProps({testID: 'github-account-profile-link'});
    expect(profileLink?.props.accessibilityRole).toBe('link');
    expect(profileLink?.findByType(Text).props.children).toBe('github.com/octocat');

    await ReactTestRenderer.act(async () => {
      renderer?.root.findByProps({testID: 'github-account-back'}).props.onPress();
      renderer?.root.findByProps({testID: 'github-account-logout'}).props.onPress();
    });
    expect(onBack).toHaveBeenCalledTimes(1);
    expect(onLogout).toHaveBeenCalledTimes(1);

    await ReactTestRenderer.act(async () => {
      renderer?.unmount();
    });
  });

  test('shows logout feedback and handles accounts without an avatar', async () => {
    let renderer: ReactTestRenderer.ReactTestRenderer | undefined;
    await ReactTestRenderer.act(async () => {
      renderer = ReactTestRenderer.create(
        <GithubAccountScreen
          account={{username: 'octocat'}}
          error="GitHub logout did not complete. The account is still connected."
          onBack={() => undefined}
          onLogout={() => undefined}
        />,
      );
    });

    expect(renderer?.root.findByProps({testID: 'github-account-error'}).props.children).toBe('GitHub logout did not complete. The account is still connected.');
    expect(renderer?.root.findAllByProps({testID: 'github-account-avatar'})).toHaveLength(0);
    expect(renderer?.root.findByProps({testID: 'github-account-avatar-fallback'}).props.children).toBe('GH');
    await ReactTestRenderer.act(async () => {
      renderer?.unmount();
    });
  });
});
