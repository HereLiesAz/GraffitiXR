import React, { useState } from 'react';
import { StyleSheet } from 'react-native';
import { ViroARScene, ViroText, ViroConstants, ViroTrackingStateConstants } from '@reactvision/react-viro';

const ARScene = () => {
  const [text, setText] = useState('Initializing AR...');

  const onInitialized = (state: any, reason: any) => {
    if (state === ViroTrackingStateConstants.TRACKING_NORMAL) {
      setText('Hello World!');
    } else if (state === ViroTrackingStateConstants.TRACKING_UNAVAILABLE) {
      setText('Move your device!');
    }
  };

  return (
    <ViroARScene onTrackingUpdated={onInitialized}>
      <ViroText
        text={text}
        scale={[0.5, 0.5, 0.5]}
        position={[0, 0, -1]}
        style={styles.helloWorldTextStyle}
      />
    </ViroARScene>
  );
};

const styles = StyleSheet.create({
  helloWorldTextStyle: {
    fontFamily: 'Arial',
    fontSize: 30,
    color: '#ffffff',
    textAlignVertical: 'center',
    textAlign: 'center',
  },
});

export default ARScene;
