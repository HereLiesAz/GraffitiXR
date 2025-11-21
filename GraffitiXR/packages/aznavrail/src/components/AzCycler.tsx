import React, { useState, useEffect, useRef } from 'react';
import { AzButton } from './AzButton';
import { AzButtonShape } from '../types';
import { ViewStyle } from 'react-native';

interface AzCyclerProps {
  options: string[];
  selectedOption: string;
  onCycle: (option: string) => void;
  color?: string;
  shape?: AzButtonShape;
  style?: ViewStyle;
  disabled?: boolean;
  disabledOptions?: string[];
  testID?: string;
}

export const AzCycler: React.FC<AzCyclerProps> = ({
  options,
  selectedOption,
  onCycle,
  color,
  shape,
  style,
  disabled,
  disabledOptions = [],
  testID,
}) => {
  // Local state to show the currently "previewed" option
  const [displayOption, setDisplayOption] = useState(selectedOption);
  const timerRef = useRef<NodeJS.Timeout | null>(null);

  // Sync local state if external selection changes (e.g. initial load or external update)
  useEffect(() => {
    setDisplayOption(selectedOption);
  }, [selectedOption]);

  const handlePress = () => {
    if (disabled) return;

    const currentIndex = options.indexOf(displayOption);
    let nextIndex = (currentIndex + 1) % options.length;

    let nextOption = options[nextIndex];

    // Skip disabled options if any
    // Guard against infinite loop if all are disabled
    let loopCount = 0;
    while (disabledOptions.includes(nextOption) && loopCount < options.length) {
       nextIndex = (nextIndex + 1) % options.length;
       nextOption = options[nextIndex];
       loopCount++;
    }

    setDisplayOption(nextOption);

    // Reset timer
    if (timerRef.current) {
      clearTimeout(timerRef.current);
    }

    timerRef.current = setTimeout(() => {
      onCycle(nextOption);
    }, 1000);
  };

  // Cleanup timer on unmount
  useEffect(() => {
    return () => {
      if (timerRef.current) {
        clearTimeout(timerRef.current);
      }
    };
  }, []);

  return (
    <AzButton
      text={displayOption}
      onClick={handlePress}
      color={color}
      shape={shape}
      style={style}
      disabled={disabled}
      testID={testID}
    />
  );
};
