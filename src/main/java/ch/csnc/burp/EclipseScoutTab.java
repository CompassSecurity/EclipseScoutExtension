package ch.csnc.burp;

import java.awt.Component;
import javax.swing.JPanel;

public class EclipseScoutTab {

  private JPanel panel;

  public EclipseScoutTab() {
    initializeUi();
  }

  private void initializeUi() {
    this.panel = new JPanel();
  }

  public Component getComponent() {
    return this.panel;
  }
  
}
