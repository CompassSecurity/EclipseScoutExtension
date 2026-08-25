package ch.csnc.burp;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.KeyEventDispatcher;
import java.awt.KeyboardFocusManager;
import java.awt.event.ActionEvent;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.InputEvent;
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

import javax.swing.AbstractAction;
import javax.swing.DropMode;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.KeyStroke;

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
  private EclipseScoutVariableTableModel variableTableModel;
  private JTable variableTable;
  private HttpRequestEditor requestEditor;
  private HttpResponseEditor responseEditor;
  private JPanel panel;

  private int selectedRow = -1;
  private volatile boolean sequenceRunning = false;
  private volatile boolean cancelRequested = false;

  /**
   * A replay in progress that was kicked off from {@link #handleHttpRequestToBeSent}: the
   * sequence to keep replaying, the exchange whose response is still outstanding, and, if the
   * originating request was tagged with {@link EclipseScoutExchange#RESPONSE_HEADER}, the "#" of
   * the row whose response should be shown back to the caller once replay completes.
   */
  private record PendingSequence(EclipseScoutSequence sequence, EclipseScoutExchange targetExchange, Integer showNr) {
  }

  /** Pending sequence replays, keyed by the Burp message id of their outstanding request. */
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

    var updateHeader = new JButton("Update header");
    updateHeader.addActionListener(event -> {
      var nameField = new JTextField();
      var valueField = new JTextField(20);
      var removeCheckbox = new JCheckBox("Remove");
      removeCheckbox.addActionListener(
          checkboxEvent -> valueField.setEnabled(!removeCheckbox.isSelected()));
      var fields = new JPanel(new MigLayout("wrap 2", "[][grow, fill]"));
      fields.add(new JLabel("Header name:"));
      fields.add(nameField);
      fields.add(new JLabel("Header value:"));
      fields.add(valueField);
      fields.add(new JLabel());
      fields.add(removeCheckbox);
      var result = JOptionPane.showConfirmDialog(this.panel, fields, "Update header", JOptionPane.OK_CANCEL_OPTION);
      if (result != JOptionPane.OK_OPTION) {
        return;
      }
      var name = nameField.getText().strip();
      if (name.isEmpty()) {
        return;
      }
      this.commitEditorEdits();
      if (removeCheckbox.isSelected()) {
        this.tableModel.getExchanges().forEach(exchange -> exchange.removeHeader(name));
      } else {
        var value = valueField.getText();
        this.tableModel.getExchanges().forEach(exchange -> exchange.setHeader(name, value));
      }
      this.tableModel.fireTableDataChanged();
      if (this.selectedRow >= 0) {
        var modelIndex = this.table.convertRowIndexToModel(this.selectedRow);
        this.requestEditor.setRequest(this.tableModel.getExchangeAt(modelIndex).request());
      }
    });

    var actions = new JPanel();
    actions.setLayout(new MigLayout("wrap 1", "[160!]"));
    actions.add(this.sendSequence, "growx");
    actions.add(updateHeader, "growx");

    this.tableModel = new EclipseScoutTableModel();

    this.variableTableModel = new EclipseScoutVariableTableModel(exchangeNr -> this.tableModel.getExchanges().stream()
        .filter(exchange -> exchangeNr.equals(exchange.nr()))
        .findFirst()
        .orElse(null));
    this.tableModel.addTableModelListener(event -> this.variableTableModel.refreshPreview());

    Runnable tableDeleteSelectedItems = () -> {
      var requestResponsesToDelete = new ArrayList<EclipseScoutExchange>();
      for (int viewIndex : this.table.getSelectedRows()) {
        var modelIndex = this.table.convertRowIndexToModel(viewIndex);
        requestResponsesToDelete.add(this.tableModel.getExchangeAt(modelIndex));
      }
      requestResponsesToDelete.forEach(this.tableModel::removeRequestResponse);
    };

    Runnable tableSendToRepeater = () -> {
      this.commitEditorEdits();
      for (int viewIndex : this.table.getSelectedRows()) {
        var modelIndex = this.table.convertRowIndexToModel(viewIndex);
        var exchange = this.tableModel.getExchangeAt(modelIndex);
        EclipseScoutExtension.api.repeater().sendToRepeater(exchange.request());
      }
    };

    Runnable tableSendToIntruder = () -> {
      this.commitEditorEdits();
      for (int viewIndex : this.table.getSelectedRows()) {
        var modelIndex = this.table.convertRowIndexToModel(viewIndex);
        var exchange = this.tableModel.getExchangeAt(modelIndex);
        EclipseScoutExtension.api.intruder().sendToIntruder(exchange.request());
      }
    };

    Runnable tableSendToOrganizer = () -> {
      this.commitEditorEdits();
      for (int viewIndex : this.table.getSelectedRows()) {
        var modelIndex = this.table.convertRowIndexToModel(viewIndex);
        var exchange = this.tableModel.getExchangeAt(modelIndex);
        var request = exchange.request().withRemovedHeader(EclipseScoutExchange.REQUEST_HEADER);
        EclipseScoutExtension.api.organizer()
            .sendToOrganizer(HttpRequestResponse.httpRequestResponse(request, exchange.response()));
      }
    };

    this.table = new JTable(this.tableModel);
    this.table.setDragEnabled(true);
    this.table.setDropMode(DropMode.INSERT_ROWS);
    this.table.setTransferHandler(new EclipseScoutTableRowTransferHandler(this.table, this.tableModel));
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
    // Clicking a column header sorts the table by that column instead of the usual Swing
    // header-drag-to-reorder-columns behavior.
    this.table.getTableHeader().addMouseListener(new MouseAdapter() {
      @Override
      public void mouseClicked(MouseEvent e) {
        var viewColumn = table.columnAtPoint(e.getPoint());
        if (viewColumn < 0) {
          return;
        }
        var modelColumn = table.convertColumnIndexToModel(viewColumn);
        var model = (EclipseScoutTableModel) table.getModel();
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
            commitEditorEdits();
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

    this.bindShortcut(this.table, JComponent.WHEN_FOCUSED,
        KeyStroke.getKeyStroke(KeyEvent.VK_R, InputEvent.CTRL_DOWN_MASK), "sendToRepeater", tableSendToRepeater);
    this.bindShortcut(this.table, JComponent.WHEN_FOCUSED,
        KeyStroke.getKeyStroke(KeyEvent.VK_I, InputEvent.CTRL_DOWN_MASK), "sendToIntruder", tableSendToIntruder);
    this.bindShortcut(this.table, JComponent.WHEN_FOCUSED,
        KeyStroke.getKeyStroke(KeyEvent.VK_O, InputEvent.CTRL_DOWN_MASK), "sendToOrganizer", tableSendToOrganizer);
    this.bindShortcut(this.table, JComponent.WHEN_FOCUSED, KeyStroke.getKeyStroke(KeyEvent.VK_DELETE, 0),
        "deleteSelectedItems", tableDeleteSelectedItems);
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

  /**
   * Flushes whatever is currently in the request editor for the selected row into the table
   * model, if it differs from what's stored there. Called before any action that reads the table
   * model (sending a sequence, or sending a row to Repeater/Intruder/Organizer), since the
   * editor's own "modified" flag can otherwise go uncommitted - e.g. a paste followed immediately
   * by clicking a button, with no intervening key event for the old key-driven commit hook to
   * catch.
   */
  private void commitEditorEdits() {
    if (this.selectedRow >= 0 && this.requestEditor.isModified()) {
      var modelIndex = this.table.convertRowIndexToModel(this.selectedRow);
      var editedRequestResponse = HttpRequestResponse.httpRequestResponse(this.requestEditor.getRequest(),
          this.responseEditor.getResponse());
      this.tableModel.setRequestResponseAt(modelIndex, editedRequestResponse);
    }
  }

  /**
   * Binds {@code keyStroke} on {@code component}'s input map under {@code condition} to run
   * {@code action} - used to give the table's context-menu items (Send to Repeater/Intruder/
   * Organizer, Delete selected items) keyboard shortcuts without duplicating their logic.
   *
   * <p>Deliberately table-only, not ancestor/window-scoped: Ctrl+R/I are Burp's own built-in
   * shortcuts on its request/response editor component, which claims them at a level extension
   * code can't override, so binding these any higher than {@link #table} itself would just be
   * dead code while the editor has focus. {@link EclipseScoutExchange#REQUEST_HEADER} being baked
   * into every exchange's request (see {@link EclipseScoutExchange#setNr(int)}) is what makes
   * Burp's own Ctrl+R/I from the editor still work correctly with this extension's sequence
   * replay, without needing to intercept the keystroke at all.
   */
  private void bindShortcut(JComponent component, int condition, KeyStroke keyStroke, String name, Runnable action) {
    component.getInputMap(condition).put(keyStroke, name);
    component.getActionMap().put(name, new AbstractAction() {
      @Override
      public void actionPerformed(ActionEvent event) {
        action.run();
      }
    });
  }

  public void sendSequence() {
    this.commitEditorEdits();
    var requestResponses = this.getRequestResponesAsDisplayed();
    var sequence = new EclipseScoutSequence(requestResponses, this.variableTableModel.getVariables());
    while (sequence.hasNext() && !this.cancelRequested) {
      var exchange = sequence.next();
      exchange.send();
      this.updateRowResponse(exchange);
    }
  }

  /**
   * Writes {@code exchange}'s current response back onto the table row sharing its static "#",
   * so the sequence table reflects what a replay actually returned, and refreshes the response
   * editor too if that row happens to be the selected one - otherwise it keeps showing whatever
   * was in it before the replay until the row is reselected. Deliberately leaves the row's own
   * captured request untouched - {@link EclipseScoutSequence} clones exchanges before replaying
   * them precisely so each run starts from the pristine originally-captured request, which its
   * id remapping and renumbering depend on.
   */
  private void updateRowResponse(EclipseScoutExchange exchange) {
    if (exchange.nr() == null) {
      return;
    }
    this.tableModel.setResponseForNr(exchange.nr(), exchange.response());
    if (this.selectedRow >= 0) {
      var modelIndex = this.table.convertRowIndexToModel(this.selectedRow);
      if (exchange.nr().equals(this.tableModel.getExchangeAt(modelIndex).nr())) {
        this.responseEditor.setResponse(exchange.response());
      }
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
      if (requestToBeSent.hasHeader(EclipseScoutExchange.REQUEST_HEADER)) {
        var nr = Integer.parseInt(requestToBeSent.headerValue(EclipseScoutExchange.REQUEST_HEADER));
        var showNr = requestToBeSent.hasHeader(EclipseScoutExchange.RESPONSE_HEADER)
            ? Integer.parseInt(requestToBeSent.headerValue(EclipseScoutExchange.RESPONSE_HEADER))
            : null;
        var request = requestToBeSent.withRemovedHeader(EclipseScoutExchange.REQUEST_HEADER)
            .withRemovedHeader(EclipseScoutExchange.RESPONSE_HEADER);

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
      this.updateRowResponse(pending.targetExchange());
      this.cancelRequested = false;
      this.sequenceRunning = true;
      this.sendSequence.setText("Cancel sequence");
      try {
        while (pending.sequence().hasNext() && !this.cancelRequested) {
          var exchange = pending.sequence().next();
          exchange.send();
          this.updateRowResponse(exchange);
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
