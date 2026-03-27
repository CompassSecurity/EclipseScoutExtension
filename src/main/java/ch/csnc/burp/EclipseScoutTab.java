package ch.csnc.burp;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.KeyEventDispatcher;
import java.awt.KeyboardFocusManager;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;

import javax.swing.JButton;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTable;

import org.json.JSONObject;

import burp.api.montoya.http.message.HttpRequestResponse;
import burp.api.montoya.ui.editor.EditorOptions;
import burp.api.montoya.ui.editor.HttpRequestEditor;
import burp.api.montoya.ui.editor.HttpResponseEditor;
import net.miginfocom.swing.MigLayout;

public class EclipseScoutTab {

  private JButton sendSequence;
  private EclipseScoutTableModel tableModel;
  private JTable table;
  private HttpRequestEditor requestEditor;
  private HttpResponseEditor responseEditor;
  private JPanel panel;

  private int selectedRow = -1;

  public EclipseScoutTab() {
    this.sendSequence = new JButton("Send sequence");
    this.sendSequence.addActionListener(event -> {
      CompletableFuture.runAsync(() -> {
        var uiSessionId = "";
        for (int viewIndex = 0; viewIndex < this.table.getRowCount(); viewIndex++) {
          var modelIndex = this.table.convertRowIndexToModel(viewIndex);
          var requestResponse = this.tableModel.getRequestResponses().get(modelIndex);
          if (requestResponse.sequenceNr().isPresent()) {
            var sequenceNr = requestResponse.sequenceNr().get();
            if (sequenceNr == 0) {
              var updatedRequestResponse = EclipseScoutExtension.api.http().sendRequest(requestResponse.request());
              this.tableModel.setRequestResponseAt(modelIndex, updatedRequestResponse);
              var jsonObj = new JSONObject(updatedRequestResponse.response().bodyToString());
              uiSessionId = jsonObj.getJSONObject("startupData").getString("uiSessionId");
              if (uiSessionId == null || uiSessionId.isBlank()) {
                throw new RuntimeException("uiSessionId is '%s'".formatted(uiSessionId));
              }
            } else {
              requestResponse.setUiSessionId(uiSessionId);
              var updatedRequestResponse = EclipseScoutExtension.api.http().sendRequest(requestResponse.request());
              this.tableModel.setRequestResponseAt(modelIndex, updatedRequestResponse);
            }
          } else {
            var updatedRequestResponse = EclipseScoutExtension.api.http().sendRequest(requestResponse.request());
            this.tableModel.setRequestResponseAt(modelIndex, updatedRequestResponse);
          }
        }
      }).exceptionally(exc -> {
        EclipseScoutExtension.api.logging().logToError(exc);
        return null;
      });
    });

    var actions = new JPanel();
    actions.setLayout(new MigLayout());
    actions.add(this.sendSequence);
    
    this.tableModel = new EclipseScoutTableModel();

    var tableDeleteSelectedItems = new JMenuItem("Delete selected items");
    tableDeleteSelectedItems.addActionListener(event -> {
      var requestResponsesToDelete = new ArrayList<EclipseScoutHttpRequestResponse>();
      for (int viewIndex : this.table.getSelectedRows()) {
        var modelIndex = this.table.convertRowIndexToModel(viewIndex);
        requestResponsesToDelete.add(this.tableModel.getRequestResponseAt(modelIndex));
      }
      requestResponsesToDelete.forEach(this.tableModel::removeRequestResponse);
    });

    var tableContextMenu = new JPopupMenu();
    tableContextMenu.add(tableDeleteSelectedItems);

    this.table = new JTable(this.tableModel);
    this.table.setRowSorter(this.tableModel.getTableRowSorter());
    this.table.setComponentPopupMenu(tableContextMenu);
    this.table.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
    this.table.getColumnModel().getColumn(2).setPreferredWidth(200);
    this.table.getColumnModel().getColumn(3).setPreferredWidth(400);
    this.table.getSelectionModel().addListSelectionListener(event -> {
      if (!event.getValueIsAdjusting()) {
        this.selectedRow = this.table.getSelectedRow();
        if (selectedRow >= 0) {
          var modelIndex = this.table.convertRowIndexToModel(selectedRow);
          var requestResponse = this.tableModel.getRequestResponseAt(modelIndex);
          requestEditor.setRequest(requestResponse.request());
          responseEditor.setResponse(requestResponse.response());
        }
      }
    });

    var actionsAndTable = new JPanel();
    actionsAndTable.setLayout(new MigLayout("fill"));
    actionsAndTable.add(actions, "dock west");
    actionsAndTable.add(new JScrollPane(this.table), "grow");

    this.requestEditor = EclipseScoutExtension.api.userInterface().createHttpRequestEditor();
    this.responseEditor = EclipseScoutExtension.api.userInterface().createHttpResponseEditor(EditorOptions.READ_ONLY);

    var requestAndResponseEditor = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, this.requestEditor.uiComponent(), this.responseEditor.uiComponent());
    requestAndResponseEditor.setResizeWeight(0.5);
    
    var actionsAndTableAndEditors = new JSplitPane(JSplitPane.VERTICAL_SPLIT, actionsAndTable, requestAndResponseEditor);
    
    this.panel = new JPanel();
    this.panel.setLayout(new BorderLayout());
    this.panel.add(actionsAndTableAndEditors, BorderLayout.CENTER);
    this.panel.addComponentListener(new ComponentAdapter() {
      private KeyEventDispatcher keyEventDispatcher = new KeyEventDispatcher() {
        @Override
        public boolean dispatchKeyEvent(KeyEvent event) {
          try {
            if (selectedRow >= 0 && requestEditor.isModified()) {
              var modelIndex = table.convertRowIndexToModel(selectedRow);
              var editedRequestResponse = HttpRequestResponse.httpRequestResponse(requestEditor.getRequest(), responseEditor.getResponse());
              tableModel.setRequestResponseAt(modelIndex, editedRequestResponse);
            }
          } catch (Exception exc) {
            EclipseScoutExtension.api.logging().logToError(exc);
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

