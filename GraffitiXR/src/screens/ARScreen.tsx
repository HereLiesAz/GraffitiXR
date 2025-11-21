import React from 'react';
import { StyleSheet } from 'react-native';
import { ViroARSceneNavigator } from '@reactvision/react-viro';
import ARScene from './ARScene';

const InitialARScene = () => {
  return (
    <ViroARSceneNavigator
      initialScene={{
        scene: ARScene,
      }}
      style={styles.f1}
    />
  );
};

const styles = StyleSheet.create({
  f1: { flex: 1 },
});

export default InitialARScene;
