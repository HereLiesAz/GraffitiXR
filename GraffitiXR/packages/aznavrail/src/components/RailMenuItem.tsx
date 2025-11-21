import React, { useState, useEffect, useRef } from 'react';
import { View, Text, TouchableOpacity, StyleSheet } from 'react-native';
import { AzNavItem } from '../types';

interface RailMenuItemProps {
    item: AzNavItem;
    depth: number;
    isExpandedHost: boolean;
    onToggleHost: () => void;
    onItemClick: () => void;
    renderSubItems: () => React.ReactNode;
}

export const RailMenuItem: React.FC<RailMenuItemProps> = ({
    item,
    depth,
    isExpandedHost,
    onToggleHost,
    onItemClick,
    renderSubItems
}) => {
    const [displayOption, setDisplayOption] = useState(item.selectedOption);
    const timerRef = useRef<NodeJS.Timeout | null>(null);

    useEffect(() => {
        setDisplayOption(item.selectedOption);
    }, [item.selectedOption]);

    const handlePress = () => {
        if (item.isHost) {
            onToggleHost();
        } else if (item.isCycler && item.options) {
             const options = item.options;
             const currentIndex = options.indexOf(displayOption || '');
             const nextIndex = (currentIndex + 1) % options.length;
             const nextOption = options[nextIndex];
             setDisplayOption(nextOption);

             if (timerRef.current) clearTimeout(timerRef.current);
             timerRef.current = setTimeout(() => {
                 if (item.onClick) item.onClick();
             }, 1000);
        } else {
            onItemClick();
        }
    };

    const displayText = item.text || (item.isChecked ? item.toggleOnText : item.toggleOffText) || displayOption || '';

    return (
        <View>
            <TouchableOpacity
                onPress={handlePress}
                style={[styles.menuItem, { paddingLeft: 16 + (depth * 16) }]}
                accessibilityRole="menuitem"
                accessibilityLabel={displayText}
                accessibilityState={{ expanded: item.isHost ? isExpandedHost : undefined }}
            >
                <Text style={[styles.menuItemText, { fontWeight: item.isHost ? 'bold' : 'normal' }]}>
                    {displayText}
                </Text>
                {item.isHost && (
                    <Text>{isExpandedHost ? '▲' : '▼'}</Text>
                )}
            </TouchableOpacity>

            {item.isHost && isExpandedHost && renderSubItems()}
        </View>
    );
};

const styles = StyleSheet.create({
  menuItem: {
      padding: 16,
      flexDirection: 'row',
      justifyContent: 'space-between',
      alignItems: 'center',
  },
  menuItemText: {
      fontSize: 16,
  },
});
