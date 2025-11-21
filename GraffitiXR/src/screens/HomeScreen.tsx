import React from 'react';
import { View, Text, StyleSheet, Button } from 'react-native';

const HomeScreen = ({ navigation }: any) => {
  return (
    <View style={styles.container}>
      <Text style={styles.title}>GraffitiXR</Text>
      <Text style={styles.subtitle}>Visualize your art in the real world.</Text>
      <Button title="Start AR" onPress={() => navigation.navigate('AR')} />
      <Button title="Trace Mode" onPress={() => navigation.navigate('Trace')} />
    </View>
  );
};

const styles = StyleSheet.create({
  container: {
    flex: 1,
    justifyContent: 'center',
    alignItems: 'center',
    backgroundColor: '#000',
  },
  title: {
    fontSize: 32,
    color: '#fff',
    fontWeight: 'bold',
  },
  subtitle: {
    fontSize: 16,
    color: '#ccc',
    marginBottom: 20,
  },
});

export default HomeScreen;
