import React from 'react';
import { View } from 'react-native';
import { NavigationContainer, useNavigation } from '@react-navigation/native';
import { createNativeStackNavigator } from '@react-navigation/native-stack';
import HomeScreen from '../screens/HomeScreen';
import ARScreen from '../screens/ARScreen';
import TraceScreen from '../screens/TraceScreen';
import SettingsScreen from '../screens/SettingsScreen';
import { AzNavRail, AzRailItem } from 'aznavrail-react-native';

const Stack = createNativeStackNavigator();

const MainLayout = ({ children }: { children: React.ReactNode }) => {
  const navigation = useNavigation();

  return (
    <AzNavRail
        expandedRailWidth={200}
    >
      <AzRailItem id="home" text="Home" onClick={() => navigation.navigate('Home' as never)} />
      <AzRailItem id="ar" text="AR" onClick={() => navigation.navigate('AR' as never)} />
      <AzRailItem id="trace" text="Trace" onClick={() => navigation.navigate('Trace' as never)} />
      <AzRailItem id="settings" text="Settings" onClick={() => navigation.navigate('Settings' as never)} />

      <View style={{ flex: 1, backgroundColor: 'black' }}>
        {children}
      </View>
    </AzNavRail>
  );
};

const AppNavigator = () => {
  return (
    <NavigationContainer>
      <MainLayout>
        <Stack.Navigator screenOptions={{ headerShown: false }}>
          <Stack.Screen name="Home" component={HomeScreen} />
          <Stack.Screen name="AR" component={ARScreen} />
          <Stack.Screen name="Trace" component={TraceScreen} />
          <Stack.Screen name="Settings" component={SettingsScreen} />
        </Stack.Navigator>
      </MainLayout>
    </NavigationContainer>
  );
};

export default AppNavigator;
