import React from 'react';
import renderer from 'react-test-renderer';
import { AzNavRail } from '../AzNavRail';
import { AzRailItem, AzMenuItem } from '../AzNavRailScope';

describe('AzNavRail', () => {
  it('renders correctly with rail items', () => {
    const tree = renderer.create(
      <AzNavRail>
        <AzRailItem id="1" text="Home" onClick={() => {}} />
        <AzMenuItem id="2" text="Settings" onClick={() => {}} />
      </AzNavRail>
    ).toJSON();
    expect(tree).toMatchSnapshot();
  });

  it('renders correctly when expanded', () => {
      const tree = renderer.create(
        <AzNavRail initiallyExpanded={true}>
          <AzRailItem id="1" text="Home" onClick={() => {}} />
          <AzMenuItem id="2" text="Settings" onClick={() => {}} />
        </AzNavRail>
      ).toJSON();
      expect(tree).toMatchSnapshot();
    });
});
