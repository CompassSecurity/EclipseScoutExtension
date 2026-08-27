package ch.csnc.burp;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import javax.swing.AbstractAction;
import javax.swing.DefaultCellEditor;
import javax.swing.DropMode;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JMenu;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.table.DefaultTableCellRenderer;

import burp.api.montoya.core.Range;
import burp.api.montoya.http.message.HttpRequestResponse;
import burp.api.montoya.http.message.requests.HttpRequest;
import burp.api.montoya.ui.Selection;
import burp.api.montoya.ui.contextmenu.ContextMenuEvent;
import burp.api.montoya.ui.editor.EditorOptions;
import burp.api.montoya.ui.editor.HttpRequestEditor;
import burp.api.montoya.ui.editor.HttpResponseEditor;

import net.miginfocom.swing.MigLayout;

/**
 * One named sequence: its own row table, its own variables table, and its own request/response
 * editor pair, all shown as one tab in {@link EclipseScoutExtension}'s {@code tabs}. Multiple
 * instances can exist side by side - see {@link EclipseScoutExtension#createSequenceTab}.
 */
public class EclipseScoutSequenceTab {

  private final EclipseScoutExtension extension;
  // Not final: each is assigned exactly once, in the constructor below, but only after a
  // listener lambda referencing it - via `this.field` - is already created earlier in that same
  // constructor. Java's definite-assignment check for `final` fields is purely textual/
  // control-flow, not deferred-execution-aware, so it rejects that even though the lambda can't
  // actually run before construction finishes; dropping `final` sidesteps the check without
  // changing the single-assignment behavior.
  private EclipseScoutTableModel tableModel;
  private JTable table;
  private EclipseScoutVariableTableModel variableTableModel;
  private JTable variableTable;
  private HttpRequestEditor requestEditor;
  private HttpResponseEditor responseEditor;
  private JButton sendSequenceButton;
  private JPanel panel;

  private int selectedRow = -1;
  private volatile boolean sequenceRunning = false;
  private volatile boolean cancelRequested = false;

  /** Builds this sequence's whole UI - table, variables, editors, actions - and wires up its listeners. */
  public EclipseScoutSequenceTab(EclipseScoutExtension extension, String name) {
    this.extension = extension;

    var statusLabel = new JLabel();
    statusLabel.setForeground(Color.DARK_GRAY);

    Consumer<String> logStatus = msg -> {
      var now = LocalDateTime.now().format(DateTimeFormatter.ofPattern("H:m:s"));
      statusLabel.setText("[%s] %s".formatted(now, msg));
    };

    logStatus.accept("initialized");

    var status = new JPanel(new MigLayout());
    status.add(statusLabel);

    this.sendSequenceButton = new JButton("Send sequence");
    this.sendSequenceButton.addActionListener(event -> {
      if (this.sequenceRunning) {
        this.cancelRequested = true;
        logStatus.accept("cancelling...");
        return;
      }
      this.beginRun();
      var updateRunTab = this.extension.openRunTab(this.getName());
      CompletableFuture.runAsync(() -> {
        try {
          logStatus.accept("sending sequence");
          this.sendSequence(
              (sequence, exchange, index) -> updateRunTab.accept(sequence.exchanges().subList(0, index + 1)));
          logStatus.accept(this.cancelRequested ? "cancelled" : "done");
        } catch (Exception exc) {
          EclipseScoutExtension.api.logging().logToError(exc);
          logStatus.accept(exc.getMessage());
        } finally {
          this.endRun();
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
      var headerName = nameField.getText().strip();
      if (headerName.isEmpty()) {
        return;
      }
      this.commitEditorEdits();
      if (removeCheckbox.isSelected()) {
        this.tableModel.getExchanges().forEach(exchange -> exchange.removeHeader(headerName));
      } else {
        var value = valueField.getText();
        this.tableModel.getExchanges().forEach(exchange -> exchange.setHeader(headerName, value));
      }
      this.tableModel.fireTableDataChanged();
      if (this.selectedRow >= 0) {
        var modelIndex = this.table.convertRowIndexToModel(this.selectedRow);
        this.requestEditor.setRequest(this.tableModel.getExchangeAt(modelIndex).request());
      }
    });

    var actions = new JPanel();
    actions.setLayout(new MigLayout("wrap 1", "[160!]"));
    actions.add(this.sendSequenceButton, "growx");
    actions.add(updateHeader, "growx");

    this.tableModel = new EclipseScoutTableModel(name);

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
        var request = exchange.request().withRemovedHeader(EclipseScoutExchange.REQUEST_HEADER)
            .withRemovedHeader(EclipseScoutExchange.SEQUENCE_HEADER);
        EclipseScoutExtension.api.organizer()
            .sendToOrganizer(HttpRequestResponse.httpRequestResponse(request, exchange.response()));
      }
    };

    var tableContextMenu = new JPopupMenu();
    tableContextMenu.add(EclipseScoutExtension.menuItem("Send to Repeater", tableSendToRepeater));
    tableContextMenu.add(EclipseScoutExtension.menuItem("Send to Intruder", tableSendToIntruder));
    tableContextMenu.add(EclipseScoutExtension.menuItem("Send to Organizer", tableSendToOrganizer));
    tableContextMenu.addSeparator();
    tableContextMenu.add(EclipseScoutExtension.menuItem("Delete selected items", tableDeleteSelectedItems));

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
    // Flags a row yellow if either: it has neither a captured Req# nor a Res# - i.e. Scout's "#"
    // sequence number wasn't found in either its request or response body, so it can't be
    // correlated to the rest of the sequence by that number at all - or it's an empty "?poll"
    // (no response yet, or a response reporting no events, either missing its "#" entirely - {} -
    // or containing nothing else besides it - see EclipseScoutExchange#hasEmptyResponseObject/
    // #hasOnlyResponseNr).
    this.table.setDefaultRenderer(Object.class, new DefaultTableCellRenderer() {
      @Override
      public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected,
          boolean hasFocus, int row, int column) {
        var component = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
        if (!isSelected) {
          var exchange = EclipseScoutSequenceTab.this.tableModel
              .getExchangeAt(table.convertRowIndexToModel(row));
          var noReqOrResNr = exchange.sequenceNrRequest() == null && exchange.sequenceNrResponse() == null;
          var emptyPoll = exchange.isPollRequest() && (exchange.response() == null
              || exchange.hasOnlyResponseNr() || exchange.hasEmptyResponseObject());
          component.setBackground(noReqOrResNr || emptyPoll ? Color.YELLOW : table.getBackground());
        }
        return component;
      }
    });
    this.table.getSelectionModel().addListSelectionListener(event -> {
      if (!event.getValueIsAdjusting()) {
        this.selectedRow = this.table.getSelectedRow();
        if (this.selectedRow >= 0) {
          var modelIndex = this.table.convertRowIndexToModel(this.selectedRow);
          var requestResponse = this.tableModel.getExchangeAt(modelIndex);
          this.requestEditor.setRequest(requestResponse.request());
          this.responseEditor.setResponse(requestResponse.response());
        }
      }
    });
    // Clicking a column header sorts the table by that column instead of the usual Swing
    // header-drag-to-reorder-columns behavior.
    this.table.getTableHeader().addMouseListener(new MouseAdapter() {
      @Override
      public void mouseClicked(MouseEvent e) {
        var viewColumn = EclipseScoutSequenceTab.this.table.columnAtPoint(e.getPoint());
        if (viewColumn < 0) {
          return;
        }
        var modelColumn = EclipseScoutSequenceTab.this.table.convertColumnIndexToModel(viewColumn);
        var model = (EclipseScoutTableModel) EclipseScoutSequenceTab.this.table.getModel();
        model.sortByColumn(modelColumn);
      }
    });

    this.variableTable = new JTable(this.variableTableModel);
    this.variableTable.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
    // Without this, JTable's default behavior on losing focus to some other component (rather
    // than moving to a different cell in the same table) is to leave the in-progress edit
    // uncommitted instead of saving it - e.g. clicking "Send sequence" right after editing a
    // regex would silently drop that edit.
    this.variableTable.putClientProperty("terminateEditOnFocusLost", Boolean.TRUE);
    this.variableTable.getColumnModel().getColumn(0).setPreferredWidth(70);
    this.variableTable.getColumnModel().getColumn(1).setPreferredWidth(100);
    this.variableTable.getColumnModel().getColumn(2).setPreferredWidth(150);
    this.variableTable.getColumnModel().getColumn(3).setPreferredWidth(150);

    var regexField = new JTextField();
    var regexPatternsMenu = new JMenu("Patterns");
    regexPatternsMenu.add(EclipseScoutExtension.menuItem("([^\"]+)", () -> regexField.replaceSelection("([^\"]+)")));
    var regexPopup = new JPopupMenu();
    regexPopup.add(regexPatternsMenu);
    regexField.setComponentPopupMenu(regexPopup);
    this.variableTable.getColumnModel().getColumn(2).setCellEditor(new DefaultCellEditor(regexField));
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

    this.bindShortcut(this.table, JComponent.WHEN_FOCUSED,
        KeyStroke.getKeyStroke(KeyEvent.VK_R, InputEvent.CTRL_DOWN_MASK), "sendToRepeater", tableSendToRepeater);
    this.bindShortcut(this.table, JComponent.WHEN_FOCUSED,
        KeyStroke.getKeyStroke(KeyEvent.VK_I, InputEvent.CTRL_DOWN_MASK), "sendToIntruder", tableSendToIntruder);
    this.bindShortcut(this.table, JComponent.WHEN_FOCUSED,
        KeyStroke.getKeyStroke(KeyEvent.VK_O, InputEvent.CTRL_DOWN_MASK), "sendToOrganizer", tableSendToOrganizer);
    this.bindShortcut(this.table, JComponent.WHEN_FOCUSED, KeyStroke.getKeyStroke(KeyEvent.VK_DELETE, 0),
        "deleteSelectedItems", tableDeleteSelectedItems);
  }

  /**
   * Binds {@code keyStroke} on {@code component}'s input map under {@code condition} to run
   * {@code action} - used to give the table's context-menu items (Send to Repeater/Intruder/
   * Organizer, Delete selected items) keyboard shortcuts without duplicating their logic.
   *
   * <p>Deliberately table-only, not ancestor/window-scoped: Ctrl+R/I are Burp's own built-in
   * shortcuts on its request/response editor component, which claims them at a level extension
   * code can't override, so binding these any higher than {@link #table} itself would just be
   * dead code while the editor has focus. {@link EclipseScoutExchange#REQUEST_HEADER}/
   * {@link EclipseScoutExchange#SEQUENCE_HEADER} being baked into every exchange's request is
   * what makes Burp's own Ctrl+R/I from the editor still work correctly with this extension's
   * sequence replay, without needing to intercept the keystroke at all.
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

  /** This sequence's display name - see {@link EclipseScoutTableModel#getName}. */
  public String getName() {
    return this.tableModel.getName();
  }

  /** Renames this sequence - see {@link EclipseScoutTableModel#setName}. */
  public void rename(String name) {
    this.tableModel.setName(name);
  }

  /** This tab's content component, added to {@link EclipseScoutExtension}'s {@code tabs}. */
  public JPanel getPanel() {
    return this.panel;
  }

  public EclipseScoutTableModel getTableModel() {
    return this.tableModel;
  }

  public EclipseScoutVariableTableModel getVariableTableModel() {
    return this.variableTableModel;
  }

  /** This tab's variables table component - used by the global outside-click commit hook. */
  JTable getVariableTableComponent() {
    return this.variableTable;
  }

  /** Appends {@code requestResponse} as a new row, e.g. from the "Eclipse Scout" context menu action. */
  public void addRequestResponse(HttpRequestResponse requestResponse) {
    this.tableModel.addRequestResponse(requestResponse);
  }

  /** All exchanges in the table's current display order, i.e. after any drag-and-drop reordering or sort. */
  List<EclipseScoutExchange> getRequestResponsesAsDisplayed() {
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
  void commitEditorEdits() {
    if (this.selectedRow >= 0 && this.requestEditor.isModified()) {
      var modelIndex = this.table.convertRowIndexToModel(this.selectedRow);
      var editedRequestResponse = HttpRequestResponse.httpRequestResponse(this.requestEditor.getRequest(),
          this.responseEditor.getResponse());
      this.tableModel.setRequestResponseAt(modelIndex, editedRequestResponse);
    }
  }

  /**
   * Stops whatever cell is currently being edited in the variables table, committing its value
   * into the model - a plain {@code JTable} only does this on its own when focus moves to
   * another cell in the same table; moving focus to some other component entirely (e.g. clicking
   * "Send sequence" right after typing a regex) otherwise leaves the edit sitting uncommitted in
   * the editor component. Called from the global key/mouse hooks installed in
   * {@link EclipseScoutExtension} so a keystroke or a click anywhere outside the table (and
   * outside its own "Patterns" popup) commits whatever cell is open.
   */
  void commitVariableTableEdits() {
    if (this.variableTable.isEditing()) {
      this.variableTable.getCellEditor().stopCellEditing();
    }
  }

  /** Whether {@link #requestCancel} was called for the run currently in progress, if any. */
  boolean isCancelRequested() {
    return this.cancelRequested;
  }

  /** Requests that the run currently in progress, if any, stop after its current step. */
  void requestCancel() {
    this.cancelRequested = true;
  }

  /** Marks a replay as started: resets cancellation, flips the button to "Cancel sequence". */
  void beginRun() {
    this.cancelRequested = false;
    this.sequenceRunning = true;
    this.sendSequenceButton.setText("Cancel sequence");
  }

  /** Marks a replay as finished: clears the running/cancel flags, flips the button back. */
  void endRun() {
    this.sequenceRunning = false;
    this.cancelRequested = false;
    this.sendSequenceButton.setText("Send sequence");
  }

  /**
   * Replays every displayed row in order, from the start, sending each one directly; stops early
   * if {@link #requestCancel} is called between steps. {@code stepListener}, if non-null, is
   * notified after each step - e.g. to drive a live view of the run in progress - and is called
   * on this method's own (background) thread, not the EDT.
   */
  public void sendSequence(EclipseScoutSequence.StepListener stepListener) {
    this.commitEditorEdits();
    var requestResponses = this.getRequestResponsesAsDisplayed();
    var sequence = new EclipseScoutSequence(requestResponses, this.variableTableModel.getVariables());
    sequence.setStepListener(stepListener);
    while (sequence.hasNext() && !this.cancelRequested) {
      sequence.sendNext();
    }
  }

  /**
   * Whether {@code event} originated from within this sequence's own request editor pane -
   * determined from the native input event's source component rather than {@code event}'s tool
   * type, since any extension-provided suite tab reports
   * {@link burp.api.montoya.core.ToolType#EXTENSIONS} regardless of which extension's UI it
   * actually came from.
   */
  boolean isOwnRequestEditor(ContextMenuEvent event) {
    var inputEvent = event.inputEvent();
    return inputEvent != null && inputEvent.getSource() instanceof Component source
        && SwingUtilities.isDescendingFrom(source, this.requestEditor.uiComponent());
  }

  /**
   * Builds a "Variables" submenu listing every declared variable with a non-blank name, or
   * {@link Optional#empty()} if none are declared. Selecting an entry inserts a
   * {@code $VAR:name$} placeholder into {@link #requestEditor} at its current cursor position.
   */
  Optional<JMenu> buildVariablesMenu() {
    var names = this.variableTableModel.getVariables().stream()
        .map(EclipseScoutVariable::name)
        .filter(varName -> varName != null && !varName.isBlank())
        .distinct()
        .toList();
    if (names.isEmpty()) {
      return Optional.empty();
    }
    var menu = new JMenu("Variables");
    for (var varName : names) {
      var item = new JMenuItem(varName);
      item.addActionListener(actionEvent -> this.insertVariablePlaceholder(varName));
      menu.add(item);
    }
    return Optional.of(menu);
  }

  /**
   * Inserts a {@code $VAR:name$} placeholder into {@link #requestEditor}, replacing its current
   * selection if it has one, or inserting at the cursor position otherwise, and writes the result
   * straight into the table model - a programmatic {@link HttpRequestEditor#setRequest} doesn't
   * set the editor's "modified" flag, so leaving this to the usual {@link #commitEditorEdits}
   * path would silently drop the inserted placeholder once the row is no longer selected.
   */
  private void insertVariablePlaceholder(String name) {
    var request = this.requestEditor.getRequest();
    var text = request.toString();
    var range = this.requestEditor.selection()
        .map(Selection::offsets)
        .orElseGet(() -> Range.range(this.requestEditor.caretPosition(), this.requestEditor.caretPosition()));
    var updated = text.substring(0, range.startIndexInclusive()) + "$VAR:" + name + "$"
        + text.substring(range.endIndexExclusive());
    var updatedRequest = HttpRequest.httpRequest(request.httpService(), updated);
    this.requestEditor.setRequest(updatedRequest);
    if (this.selectedRow >= 0) {
      var modelIndex = this.table.convertRowIndexToModel(this.selectedRow);
      var editedRequestResponse = HttpRequestResponse.httpRequestResponse(updatedRequest,
          this.responseEditor.getResponse());
      this.tableModel.setRequestResponseAt(modelIndex, editedRequestResponse);
    }
  }

}
