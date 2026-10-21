package org.gephi.plugins.explorer3d;
import javax.swing.AbstractAction;
import java.awt.event.ActionEvent;
public final class OpenAction extends AbstractAction {
    public OpenAction(){super("3D Explorer · 三维探索");}
    public void actionPerformed(ActionEvent e){ExplorerPanel.showPanel();}
}
