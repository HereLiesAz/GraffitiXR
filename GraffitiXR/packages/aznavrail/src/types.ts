export enum AzButtonShape {
  CIRCLE = 'CIRCLE',
  SQUARE = 'SQUARE',
  RECTANGLE = 'RECTANGLE',
  NONE = 'NONE',
}

export interface AzNavRailSettings {
  displayAppNameInHeader?: boolean;
  packRailButtons?: boolean;
  expandedRailWidth?: number;
  collapsedRailWidth?: number;
  showFooter?: boolean;
  isLoading?: boolean;
  defaultShape?: AzButtonShape;
  enableRailDragging?: boolean;
}

export interface AzNavItem {
  id: string;
  text: string;
  route?: string;
  screenTitle?: string;
  isRailItem: boolean;
  color?: string;
  isToggle: boolean;
  isChecked?: boolean;
  toggleOnText: string;
  toggleOffText: string;
  isCycler: boolean;
  options?: string[];
  selectedOption?: string;
  isDivider: boolean;
  collapseOnClick: boolean;
  shape: AzButtonShape;
  disabled: boolean;
  disabledOptions?: string[];
  isHost: boolean;
  isSubItem: boolean;
  hostId?: string;
  isExpanded: boolean;
  onClick?: () => void;
}

export interface AzNavItemProps {
  id: string;
  text: string;
  route?: string;
  screenTitle?: string;
  disabled?: boolean;
  onClick?: () => void;
  color?: string;
  shape?: AzButtonShape;
}

export interface AzToggleProps extends AzNavItemProps {
  isChecked: boolean;
  toggleOnText: string;
  toggleOffText: string;
}

export interface AzCyclerProps extends AzNavItemProps {
  options: string[];
  selectedOption: string;
  disabledOptions?: string[];
}

export interface AzHostItemProps extends AzNavItemProps {}

export interface AzSubItemProps extends AzNavItemProps {
  hostId: string;
}

export interface AzSubToggleProps extends AzToggleProps {
  hostId: string;
}

export interface AzSubCyclerProps extends AzCyclerProps {
  hostId: string;
}
