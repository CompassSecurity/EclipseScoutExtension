package ch.csnc.burp;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.KeyEventDispatcher;
import java.awt.KeyboardFocusManager;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import javax.swing.DropMode;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTable;

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

  private static final String REQUEST_HEADER = "X-Scout-Request";
  private static final String RESPONSE_HEADER = "X-Scout-Response";

  private JButton sendSequence;
  private EclipseScoutTableModel tableModel;
  private JTable table;
  private EclipseScoutVariableTableModel variableTableModel;
  private JTable variableTable;
  private HttpRequestEditor requestEditor;
  private HttpResponseEditor responseEditor;
  private JPanel panel;

  private int selectedRow = -1;
  private volatile boolean sequenceRunning = false;
  private volatile boolean cancelRequested = false;

  private record PendingSequence(EclipseScoutSequence sequence, EclipseScoutExchange targetExchange, Integer showNr) {
  }

  private Map<Integer, PendingSequence> sequences = new HashMap<>();

  public EclipseScoutTab() {
    var statusLabel = new JLabel();
    statusLabel.setForeground(Color.DARK_GRAY);

    Consumer<String> logStatus = msg -> {
      var now = LocalDateTime.now().format(DateTimeFormatter.ofPattern("H:m:s"));
      statusLabel.setText("[%s] %s".formatted(now, msg));
    };

    logStatus.accept("initialized");

    var status = new JPanel(new MigLayout());
    status.add(statusLabel);

    this.sendSequence = new JButton("Send sequence");
    this.sendSequence.addActionListener(event -> {
      if (this.sequenceRunning) {
        this.cancelRequested = true;
        logStatus.accept("cancelling...");
        return;
      }
      this.cancelRequested = false;
      this.sequenceRunning = true;
      this.sendSequence.setText("Cancel sequence");
      CompletableFuture.runAsync(() -> {
        try {
          logStatus.accept("sending sequence");
          sendSequence();
          logStatus.accept(this.cancelRequested ? "cancelled" : "done");
        } catch (Exception exc) {
          EclipseScoutExtension.api.logging().logToError(exc);
          logStatus.accept(exc.getMessage());
        } finally {
          this.sequenceRunning = false;
          this.cancelRequested = false;
          this.sendSequence.setText("Send sequence");
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

    this.variableTableModel = new EclipseScoutVariableTableModel(exchangeNr -> this.tableModel.getExchanges().stream()
        .filter(exchange -> exchangeNr.equals(exchange.nr()))
        .findFirst()
        .orElse(null));
    this.tableModel.addTableModelListener(event -> this.variableTableModel.refreshPreview());

    var tableDeleteSelectedItems = new JMenuItem("Delete selected items");
    tableDeleteSelectedItems.addActionListener(event -> {
      var requestResponsesToDelete = new ArrayList<EclipseScoutExchange>();
      for (int viewIndex : this.table.getSelectedRows()) {
        var modelIndex = this.table.convertRowIndexToModel(viewIndex);
        requestResponsesToDelete.add(this.tableModel.getExchangeAt(modelIndex));
      }
      requestResponsesToDelete.forEach(this.tableModel::removeRequestResponse);
    });

    var tableSendToRepeater = new JMenuItem("Send to Repeater");
    tableSendToRepeater.addActionListener(event -> {
      for (int viewIndex : this.table.getSelectedRows()) {
        var modelIndex = this.table.convertRowIndexToModel(viewIndex);
        var exchange = this.tableModel.getExchangeAt(modelIndex);
        var request = exchange.request().withHeader(REQUEST_HEADER, String.valueOf(exchange.nr()));
        EclipseScoutExtension.api.repeater().sendToRepeater(request);
      }
    });

    var tableSendToIntruder = new JMenuItem("Send to Intruder");
    tableSendToIntruder.addActionListener(event -> {
      for (int viewIndex : this.table.getSelectedRows()) {
        var modelIndex = this.table.convertRowIndexToModel(viewIndex);
        var exchange = this.tableModel.getExchangeAt(modelIndex);
        var request = exchange.request().withHeader(REQUEST_HEADER, String.valueOf(exchange.nr()));
        EclipseScoutExtension.api.intruder().sendToIntruder(request);
      }
    });

    var tableSendToOrganizer = new JMenuItem("Send to Organizer");
    tableSendToOrganizer.addActionListener(event -> {
      for (int viewIndex : this.table.getSelectedRows()) {
        var modelIndex = this.table.convertRowIndexToModel(viewIndex);
        var exchange = this.tableModel.getExchangeAt(modelIndex);
        EclipseScoutExtension.api.organizer().sendToOrganizer(exchange.requestResponse());
      }
    });

    var tableContextMenu = new JPopupMenu();
    tableContextMenu.add(tableSendToRepeater);
    tableContextMenu.add(tableSendToIntruder);
    tableContextMenu.add(tableSendToOrganizer);
    tableContextMenu.addSeparator();
    tableContextMenu.add(tableDeleteSelectedItems);

    this.table = new JTable(this.tableModel);
    this.table.setDragEnabled(true);
    this.table.setDropMode(DropMode.INSERT_ROWS);
    this.table.setTransferHandler(new EclipseScoutTableRowTransferHandler(this.table, this.tableModel));
    this.table.setComponentPopupMenu(tableContextMenu);
    this.table.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
    this.table.getColumnModel().getColumn(0).setPreferredWidth(50);
    this.table.getColumnModel().getColumn(1).setPreferredWidth(50);
    this.table.getColumnModel().getColumn(2).setPreferredWidth(50);
    this.table.getColumnModel().getColumn(3).setPreferredWidth(100);
    this.table.getColumnModel().getColumn(4).setPreferredWidth(200);
    this.table.getColumnModel().getColumn(5).setPreferredWidth(400);
    this.table.getSelectionModel().addListSelectionListener(event -> {
      if (!event.getValueIsAdjusting()) {
        this.selectedRow = this.table.getSelectedRow();
        if (selectedRow >= 0) {
          var modelIndex = this.table.convertRowIndexToModel(selectedRow);
          var requestResponse = this.tableModel.getExchangeAt(modelIndex);
          requestEditor.setRequest(requestResponse.request());
          responseEditor.setResponse(requestResponse.response());
        }
      }
    });
    this.table.getTableHeader().addMouseListener(new MouseAdapter() {
      @Override
      public void mouseClicked(MouseEvent e) {
        int viewColumn = table.columnAtPoint(e.getPoint());
        if (viewColumn < 0) {
          return;
        }
        int modelColumn = table.convertColumnIndexToModel(viewColumn);
        EclipseScoutTableModel model = (EclipseScoutTableModel) table.getModel();
        model.sortByColumn(modelColumn);
      }
    });

    this.variableTable = new JTable(this.variableTableModel);
    this.variableTable.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
    this.variableTable.getColumnModel().getColumn(0).setPreferredWidth(70);
    this.variableTable.getColumnModel().getColumn(1).setPreferredWidth(100);
    this.variableTable.getColumnModel().getColumn(2).setPreferredWidth(150);
    this.variableTable.getColumnModel().getColumn(3).setPreferredWidth(150);
    this.variableTable.setPreferredScrollableViewportSize(
        new Dimension(this.variableTable.getPreferredScrollableViewportSize().width,
            this.variableTable.getRowHeight() * 5));

    var addVariable = new JButton("+");
    addVariable.addActionListener(event -> {
      Integer exchangeNr = null;
      if (this.selectedRow >= 0) {
        var modelIndex = this.table.convertRowIndexToModel(this.selectedRow);
        exchangeNr = this.tableModel.getExchangeAt(modelIndex).nr();
      }
      this.variableTableModel.addVariable(exchangeNr);
    });

    var removeVariable = new JButton("-");
    removeVariable.addActionListener(event -> {
      var variablesToRemove = new ArrayList<EclipseScoutVariable>();
      for (int viewIndex : this.variableTable.getSelectedRows()) {
        var modelIndex = this.variableTable.convertRowIndexToModel(viewIndex);
        variablesToRemove.add(this.variableTableModel.getVariableAt(modelIndex));
      }
      this.variableTableModel.removeVariables(variablesToRemove);
    });

    var variableButtons = new JPanel(new MigLayout());
    variableButtons.add(new JLabel("Variables"));
    variableButtons.add(addVariable);
    variableButtons.add(removeVariable);

    var variables = new JPanel();
    variables.setLayout(new MigLayout("fill, wrap 1, insets 0"));
    variables.add(variableButtons);
    variables.add(new JScrollPane(this.variableTable), "grow, push");

    var upper = new JPanel();
    upper.setLayout(new MigLayout("fill"));
    upper.add(actions, "dock west");
    upper.add(status, "dock south, h 30!");
    upper.add(variables, "dock south, h pref!");
    upper.add(new JScrollPane(this.table), "grow");

    this.requestEditor = EclipseScoutExtension.api.userInterface().createHttpRequestEditor();
    this.responseEditor = EclipseScoutExtension.api.userInterface().createHttpResponseEditor(EditorOptions.READ_ONLY);

    var lower = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, this.requestEditor.uiComponent(),
        this.responseEditor.uiComponent());
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
              var editedRequestResponse = HttpRequestResponse.httpRequestResponse(requestEditor.getRequest(),
                  responseEditor.getResponse());
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

  private List<EclipseScoutExchange> getRequestResponesAsDisplayed() {
    var requestResponses = new ArrayList<EclipseScoutExchange>();
    for (int i = 0; i < this.table.getRowCount(); i++) {
      var modelIndex = this.table.convertRowIndexToModel(i);
      requestResponses.add(this.tableModel.getExchangeAt(modelIndex));
    }
    return requestResponses;
  }

  public void sendSequence() {
    var requestResponses = this.getRequestResponesAsDisplayed();
    var sequence = new EclipseScoutSequence(requestResponses, this.variableTableModel.getVariables());
    while (sequence.hasNext() && !this.cancelRequested) {
      sequence.next().send();
    }
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
    try {
      if (requestToBeSent.hasHeader(REQUEST_HEADER)) {
        var nr = Integer.parseInt(requestToBeSent.headerValue(REQUEST_HEADER));
        Integer showNr = requestToBeSent.hasHeader(RESPONSE_HEADER)
            ? Integer.parseInt(requestToBeSent.headerValue(RESPONSE_HEADER))
            : null;
        var request = requestToBeSent.withRemovedHeader(REQUEST_HEADER).withRemovedHeader(RESPONSE_HEADER);

        var sequence = new EclipseScoutSequence(this.getRequestResponesAsDisplayed(), this.variableTableModel.getVariables());
        var targetIndex = sequence.indexOfNr(nr);
        if (targetIndex < 0) {
          EclipseScoutExtension.api.logging().logToError("No row with # %d found, sending as-is".formatted(nr));
          return RequestToBeSentAction.continueWith(request);
        }
        var targetExchange = sequence.advanceTo(targetIndex, request);
        this.sequences.put(requestToBeSent.messageId(), new PendingSequence(sequence, targetExchange, showNr));
        return RequestToBeSentAction.continueWith(targetExchange.request());
      }
    } catch (Exception exc) {
      EclipseScoutExtension.api.logging().logToError(exc);
    }
    return RequestToBeSentAction.continueWith(requestToBeSent);
  }

  @Override
  public ResponseReceivedAction handleHttpResponseReceived(HttpResponseReceived responseReceived) {
    var pending = this.sequences.remove(responseReceived.messageId());
    if (pending != null) {
      pending.targetExchange().setResponse(responseReceived);
      this.cancelRequested = false;
      this.sequenceRunning = true;
      this.sendSequence.setText("Cancel sequence");
      try {
        while (pending.sequence().hasNext() && !this.cancelRequested) {
          pending.sequence().next().send();
        }
      } finally {
        this.sequenceRunning = false;
        this.cancelRequested = false;
        this.sendSequence.setText("Send sequence");
      }
      if (pending.showNr() != null) {
        var toShow = pending.sequence().exchangeWithNr(pending.showNr());
        if (toShow != null && toShow.response() != null) {
          return ResponseReceivedAction.continueWith(toShow.response());
        }
        EclipseScoutExtension.api.logging().logToError("No response for row # %d found".formatted(pending.showNr()));
      }
    }
    return ResponseReceivedAction.continueWith(responseReceived);
  }
}
