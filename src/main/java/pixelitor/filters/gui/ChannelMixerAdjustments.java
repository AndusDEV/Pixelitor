/*
 * Copyright 2018 Laszlo Balazs-Csiki and Contributors
 *
 * This file is part of Pixelitor. Pixelitor is free software: you
 * can redistribute it and/or modify it under the terms of the GNU
 * General Public License, version 3 as published by the Free
 * Software Foundation.
 *
 * Pixelitor is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Pixelitor. If not, see <http://www.gnu.org/licenses/>.
 */

package pixelitor.filters.gui;

import pixelitor.filters.ParametrizedFilter;
import pixelitor.gui.utils.GUIUtils;
import pixelitor.layers.Drawable;

import javax.swing.*;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.FlowLayout;

/**
 * The GUI for the "Channel Mixer"
 */
public class ChannelMixerAdjustments extends ParametrizedFilterGUIPanel {
    public ChannelMixerAdjustments(ParametrizedFilter filter, Drawable dr, Action[] actions) {
        super(filter, dr, actions, ShowOriginal.YES);
    }

    @Override
    protected void setupGUI(ParamSet params, Object otherInfo, ShowOriginal addShowOriginal) {
        JPanel upperPanel = new JPanel(new FlowLayout());
        JPanel leftPanel = GUIUtils.arrangeParamsInVerticalGridBag(params.getParamList());
        JPanel rightPanel = createPresetsPanel((Action[]) otherInfo);
        upperPanel.add(leftPanel);
        upperPanel.add(rightPanel);

        JPanel buttonsPanel = createFilterActionsPanel(params.getActionList(), addShowOriginal, 5);

        setLayout(new BorderLayout());
        add(upperPanel, BorderLayout.CENTER);
        add(buttonsPanel, BorderLayout.SOUTH);
    }

    private static JPanel createPresetsPanel(Action[] actions) {
        JPanel rightPanel = new JPanel();
        rightPanel.setLayout(new BoxLayout(rightPanel, BoxLayout.Y_AXIS));
        rightPanel.setBorder(BorderFactory.createTitledBorder("Presets"));
        for (Action action : actions) {
            JComponent b = new JButton(action);
            b.setAlignmentX(Component.LEFT_ALIGNMENT);
            rightPanel.add(b);
        }
        return rightPanel;
    }
}