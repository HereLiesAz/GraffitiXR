import React from 'react';
import { View, Text, StyleSheet } from 'react-native';
import { Camera, useCameraDevice, useCameraPermission } from 'react-native-vision-camera';

const TraceScreen = () => {
  const { hasPermission, requestPermission } = useCameraPermission();
  const device = useCameraDevice('back');

  React.useEffect(() => {
    if (!hasPermission) {
      requestPermission();
    }
  }, [hasPermission, requestPermission]);

  if (!hasPermission) return <View style={styles.container}><Text>No Camera Permission</Text></View>;
  if (device == null) return <View style={styles.container}><Text>No Device</Text></View>;

  return (
    <View style={styles.container}>
      <Camera
        style={StyleSheet.absoluteFill}
        device={device}
        isActive={true}
      />
      <View style={styles.overlay}>
        <Text style={styles.text}>Trace Mode (Overlay)</Text>
      </View>
    </View>
  );
};

const styles = StyleSheet.create({
  container: {
    flex: 1,
    backgroundColor: 'black',
  },
  overlay: {
    position: 'absolute',
    top: 50,
    alignSelf: 'center',
  },
  text: {
    color: 'white',
    fontSize: 20,
  },
});

export default TraceScreen;
