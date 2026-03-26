package ch.csnc.burp;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.KeyEventDispatcher;
import java.awt.KeyboardFocusManager;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTable;

import burp.api.montoya.http.message.HttpRequestResponse;
import burp.api.montoya.ui.editor.EditorOptions;
import burp.api.montoya.ui.editor.HttpRequestEditor;
import burp.api.montoya.ui.editor.HttpResponseEditor;

public class EclipseScoutTab {

  private EclipseScoutTableModel tableModel;
  private JTable table;
  private HttpRequestEditor requestEditor;
  private HttpResponseEditor responseEditor;
  private JPanel panel;

  private int selectedRow = -1;

  public EclipseScoutTab() {
    this.tableModel = new EclipseScoutTableModel();

    this.table = new JTable(this.tableModel);
    this.table.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
    this.table.getColumnModel().getColumn(2).setPreferredWidth(200);
    this.table.getColumnModel().getColumn(3).setPreferredWidth(400);
    this.table.addMouseListener(new MouseAdapter() {
      @Override
      public void mousePressed(MouseEvent event) {
        selectedRow = table.getSelectedRow();
        if (selectedRow >= 0) {
          var requestResponse = tableModel.getRequestResponseAt(selectedRow);
          requestEditor.setRequest(requestResponse.request());
          responseEditor.setResponse(requestResponse.response());
        }
      }
    });

    this.requestEditor = EclipseScoutExtension.api.userInterface().createHttpRequestEditor();

    this.responseEditor = EclipseScoutExtension.api.userInterface().createHttpResponseEditor(EditorOptions.READ_ONLY);

    var requestAndResponseEditor = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, this.requestEditor.uiComponent(), this.responseEditor.uiComponent());
    requestAndResponseEditor.setResizeWeight(0.5);
    
    var tableAndEditors = new JSplitPane(JSplitPane.VERTICAL_SPLIT, new JScrollPane(this.table), requestAndResponseEditor);
    
    this.panel = new JPanel();
    this.panel.setLayout(new BorderLayout());
    this.panel.add(tableAndEditors, BorderLayout.CENTER);
    this.panel.addComponentListener(new ComponentAdapter() {
      private KeyEventDispatcher keyEventDispatcher = new KeyEventDispatcher() {
        @Override
        public boolean dispatchKeyEvent(KeyEvent event) {
          if (selectedRow >= 0) {
            var editedRequestResponse = HttpRequestResponse.httpRequestResponse(requestEditor.getRequest(), responseEditor.getResponse());
            tableModel.setRequestResponseAt(selectedRow, editedRequestResponse);
          }
          return false;
        }
      };
  
      @Override
      public void componentShown(ComponentEvent event) {
        KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(this.keyEventDispatcher);        
      }

      @Override
      public void componentHidden(ComponentEvent event) {
        KeyboardFocusManager.getCurrentKeyboardFocusManager().removeKeyEventDispatcher(this.keyEventDispatcher);        
      }
    });
  }

  public Component getComponent() {
    return this.panel;
  }

  public void addRequestResponse(HttpRequestResponse requestResponse) {
    this.tableModel.addRequestResponse(requestResponse);
  }

  
}

