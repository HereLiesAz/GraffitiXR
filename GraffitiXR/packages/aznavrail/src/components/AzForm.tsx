import React, { useState, createContext, useContext } from 'react';
import { View, Text, TouchableOpacity, StyleSheet, ViewStyle } from 'react-native';
import { AzTextBox, AzTextBoxProps } from './AzTextBox';

interface AzFormContextType {
  updateField: (name: string, value: string) => void;
  formName: string;
  outlineColor: string;
  outlined: boolean;
}

const AzFormContext = createContext<AzFormContextType | undefined>(undefined);

export interface AzFormProps {
  formName: string;
  onSubmit: (data: Record<string, string>) => void;
  outlineColor?: string;
  outlined?: boolean;
  submitButtonContent?: React.ReactNode;
  children: React.ReactNode;
  style?: ViewStyle;
}

export const AzForm: React.FC<AzFormProps> = ({
  formName,
  onSubmit,
  outlineColor = '#6200ee',
  outlined = true,
  submitButtonContent,
  children,
  style,
}) => {
  const [formData, setFormData] = useState<Record<string, string>>({});

  const updateField = (name: string, value: string) => {
    setFormData(prev => ({ ...prev, [name]: value }));
  };

  const handleSubmit = () => {
    onSubmit(formData);
  };

  return (
    <AzFormContext.Provider value={{ updateField, formName, outlineColor, outlined }}>
      <View style={[styles.container, style]}>
        {children}
        <TouchableOpacity
          onPress={handleSubmit}
          style={[
            styles.submitButton,
            {
              backgroundColor: 'transparent', // Match main component background?
              borderColor: outlineColor,
              borderWidth: !outlined ? 1 : 0, // Inverse
            }
          ]}
        >
          {submitButtonContent || <Text style={{ color: outlineColor }}>Submit</Text>}
        </TouchableOpacity>
      </View>
    </AzFormContext.Provider>
  );
};

interface AzFormEntryProps extends Omit<AzTextBoxProps, 'onSubmit' | 'submitButtonContent'> {
  name: string;
}

export const AzFormEntry: React.FC<AzFormEntryProps> = ({ name, ...props }) => {
  const context = useContext(AzFormContext);
  if (!context) {
    throw new Error('AzFormEntry must be used within an AzForm');
  }

  const { updateField, formName, outlineColor, outlined } = context;

  const handleChange = (text: string) => {
    updateField(name, text);
    if (props.onValueChange) props.onValueChange(text);
  };

  return (
    <View style={{ flexDirection: 'row', marginBottom: 8 }}>
      <AzTextBox
        {...props}
        onValueChange={handleChange}
        historyContext={formName} // Use formName as history context
        outlineColor={outlineColor} // Inherit
        outlined={outlined}
        containerStyle={{ flex: 1 }}
        showSubmitButton={false}
      />
    </View>
  );
};

const styles = StyleSheet.create({
  container: {
    padding: 8,
  },
  submitButton: {
    padding: 12,
    alignItems: 'center',
    justifyContent: 'center',
    marginTop: 8,
  },
});
