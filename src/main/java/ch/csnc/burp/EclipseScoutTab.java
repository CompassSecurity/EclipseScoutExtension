package ch.csnc.burp;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.KeyEventDispatcher;
import java.awt.KeyboardFocusManager;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.KeyEvent;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTable;

import org.json.JSONObject;

import burp.api.montoya.http.handler.HttpHandler;
import burp.api.montoya.http.handler.HttpRequestToBeSent;
import burp.api.montoya.http.handler.HttpResponseReceived;
import burp.api.montoya.http.handler.RequestToBeSentAction;
import burp.api.montoya.http.handler.ResponseReceivedAction;
import burp.api.montoya.http.message.HttpRequestResponse;
import burp.api.montoya.ui.contextmenu.ContextMenuEvent;
import burp.api.montoya.ui.contextmenu.ContextMenuItemsProvider;
import burp.api.montoya.ui.editor.EditorOptions;
import burp.api.montoya.ui.editor.HttpRequestEditor;
import burp.api.montoya.ui.editor.HttpResponseEditor;
import net.miginfocom.swing.MigLayout;

public class EclipseScoutTab implements ContextMenuItemsProvider, HttpHandler {

  private JButton sendSequence;
  private EclipseScoutTableModel tableModel;
  private JTable table;
  private HttpRequestEditor requestEditor;
  private HttpResponseEditor responseEditor;
  private JPanel panel;

  private int selectedRow = -1;

  public EclipseScoutTab() {
    var statusLabel = new JLabel();
    statusLabel.setForeground(Color.DARK_GRAY);

    Consumer<String> logStatus = msg ->  {
      var now = LocalDateTime.now().format(DateTimeFormatter.ofPattern("H:m:s"));
      statusLabel.setText("[%s] %s".formatted(now, msg));
    };

    logStatus.accept("initialized");

    var status = new JPanel(new MigLayout());
    status.add(statusLabel);
    
    this.sendSequence = new JButton("Send sequence");
    this.sendSequence.addActionListener(event -> {
      CompletableFuture.runAsync(() -> {
        try {
          logStatus.accept("sending sequence");
          this.sendSequence.setEnabled(false);
          sendSequence();
          logStatus.accept("done");
        } catch (Exception exc) {
          EclipseScoutExtension.api.logging().logToError(exc);
          logStatus.accept(exc.getMessage());
        } finally {
          this.sendSequence.setEnabled(true);
        }
      }).exceptionally(exc ->  {
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

    var upper = new JPanel();
    upper.setLayout(new MigLayout("fill"));
    upper.add(actions, "dock west");
    upper.add(new JScrollPane(this.table), "grow");
    upper.add(status, "dock south, h 30!");

    this.requestEditor = EclipseScoutExtension.api.userInterface().createHttpRequestEditor();
    this.responseEditor = EclipseScoutExtension.api.userInterface().createHttpResponseEditor(EditorOptions.READ_ONLY);

    var lower = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, this.requestEditor.uiComponent(), this.responseEditor.uiComponent());
    lower.setResizeWeight(0.5);
    
    var upperLower = new JSplitPane(JSplitPane.VERTICAL_SPLIT, upper, lower);
    
    this.panel = new JPanel();
    this.panel.setLayout(new BorderLayout());
    this.panel.add(upperLower, BorderLayout.CENTER);
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

  public void sendSequence() {
    var requestResponses = this.tableModel.getRequestResponses();
    var sequence = new EclipseScoutSequence(requestResponses);
    sequence.send();
    var updatedRequestResponses = sequence.getHttpRequestResponses();
    this.tableModel.setRequestResponses(updatedRequestResponses);
  }
  
  @Override
  public List<Component> provideMenuItems(ContextMenuEvent event) {
    var sendTo = new JMenuItem("Send to %s".formatted(EclipseScoutExtension.NAME));
    sendTo.addActionListener((actionEvent) -> {
      event.selectedRequestResponses().forEach(this::addRequestResponse);
    });
    return List.of(sendTo);
  }

  @Override
  public RequestToBeSentAction handleHttpRequestToBeSent(HttpRequestToBeSent requestToBeSent) {
    return RequestToBeSentAction.continueWith(requestToBeSent);
  }

  @Override
  public ResponseReceivedAction handleHttpResponseReceived(HttpResponseReceived responseReceived) {
    return ResponseReceivedAction.continueWith(responseReceived);
  }
}

