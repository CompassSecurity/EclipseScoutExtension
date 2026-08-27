package ch.csnc.burp;

import java.awt.AWTEvent;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Insets;
import java.awt.KeyEventDispatcher;
import java.awt.KeyboardFocusManager;
import java.awt.Toolkit;
import java.awt.event.AWTEventListener;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JMenu;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.SwingUtilities;

import burp.api.montoya.BurpExtension;
import burp.api.montoya.MontoyaApi;
import burp.api.montoya.core.HighlightColor;
import burp.api.montoya.core.ToolType;
import burp.api.montoya.http.handler.HttpHandler;
import burp.api.montoya.http.handler.HttpRequestToBeSent;
import burp.api.montoya.http.handler.HttpResponseReceived;
import burp.api.montoya.http.handler.RequestToBeSentAction;
import burp.api.montoya.http.handler.ResponseReceivedAction;
import burp.api.montoya.http.message.HttpRequestResponse;
import burp.api.montoya.persistence.PersistedList;
import burp.api.montoya.ui.contextmenu.ContextMenuEvent;
import burp.api.montoya.ui.contextmenu.ContextMenuItemsProvider;
import burp.api.montoya.ui.editor.EditorOptions;
import net.miginfocom.swing.MigLayout;

public class EclipseScoutExtension implements BurpExtension, ContextMenuItemsProvider, HttpHandler {

  public static final String NAME = "Eclipse Scout";

  private static final String PERSIST_KEY_SEQUENCE_NAMES = "sequenceNames";
  private static final String PERSIST_KEY_EXCHANGES = "exchanges";
  private static final String PERSIST_KEY_EXCHANGE_NRS = "exchangeNrs";
  private static final String PERSIST_KEY_EXCHANGE_SEQUENCE_INDEX = "exchangeSequenceIndex";
  private static final String PERSIST_KEY_VARIABLE_EXCHANGE_NRS = "variableExchangeNrs";
  private static final String PERSIST_KEY_VARIABLE_NAMES = "variableNames";
  private static final String PERSIST_KEY_VARIABLE_REGEXES = "variableRegexes";
  private static final String PERSIST_KEY_VARIABLE_SEQUENCE_INDEX = "variableSequenceIndex";

  public static MontoyaApi api;

  private final List<EclipseScoutSequenceTab> sequenceTabs = new ArrayList<>();

  private JPanel panel;
  private JTabbedPane tabs;
  private int runTabCount = 0;

  /**
   * A replay in progress that was kicked off from {@link #handleHttpRequestToBeSent}: which
   * sequence it belongs to (so its "Send sequence"/"Cancel sequence" button and running state
   * can be driven correctly), the sequence to keep replaying, the exchange whose response is
   * still outstanding, and, if the originating request was tagged with
   * {@link EclipseScoutExchange#RESPONSE_HEADER}, the "#" of the row whose response should be
   * shown back to the caller once replay completes.
   */
  private record PendingSequence(EclipseScoutSequenceTab tab, EclipseScoutSequence sequence,
      EclipseScoutExchange targetExchange, Integer showNr) {
  }

  /** Pending sequence replays, keyed by the Burp message id of their outstanding request. */
  private Map<Integer, PendingSequence> sequences = new HashMap<>();

  /** Registers this extension with Burp: builds its UI tab, and hooks into the context menu and HTTP traffic. */
  @Override
  public void initialize(MontoyaApi api) {
    EclipseScoutExtension.api = api;
    api.extension().setName(NAME);
    api.logging().logToOutput("%s loaded".formatted(NAME));
    api.logging().logToOutput("Charset: %s".formatted(Charset.defaultCharset()));

    var versionTxt = "/version.txt";
    try (var stream = getClass().getResourceAsStream(versionTxt)) {
        assert stream != null;
        var reader = new BufferedReader(new InputStreamReader(stream));
        reader.lines().forEach(api.logging()::logToOutput);
    } catch (Exception exc) {
        api.logging().logToError("Could not read %s".formatted(versionTxt));
        api.logging().logToError(exc);
    }

    this.buildUi();
    try {
      this.restoreState();
    } catch (Exception exc) {
      // Whatever's wrong with previously saved state (a schema this build no longer understands,
      // corrupted persisted data, ...), the extension must still come up - registerSuiteTab and
      // friends below are what make it usable at all, and a crash here would happen before any
      // of them run. Continue with an empty UI rather than not loading.
      api.logging().logToError("Failed to restore previously saved sequences, starting empty");
      api.logging().logToError(exc);
    }
    api.userInterface().registerSuiteTab(NAME, this.getComponent());
    api.userInterface().registerContextMenuItemsProvider(this);
    api.http().registerHttpHandler(this);
    api.extension().registerUnloadingHandler(this::persistState);
  }

  /**
   * Assembles the extension's suite tab: an empty {@link #tabs} that sequences get added to (by
   * {@link #createSequenceTab}, via "New sequence" or {@link #restoreState}), plus the global
   * key/mouse hooks that keep every sequence tab's in-progress edits committed regardless of
   * where the user clicks or types next.
   */
  private void buildUi() {
    this.tabs = new JTabbedPane();

    this.panel = new JPanel();
    this.panel.setLayout(new BorderLayout());
    this.panel.add(this.tabs, BorderLayout.CENTER);
    this.panel.addComponentListener(new ComponentAdapter() {
      private KeyEventDispatcher keyEventDispatcher = event -> {
        try {
          EclipseScoutExtension.this.sequenceTabs.forEach(EclipseScoutSequenceTab::commitEditorEdits);
        } catch (Exception exc) {
          EclipseScoutExtension.api.logging().logToError(exc);
        }
        return false;
      };

      // Commits an in-progress variables-table cell edit, on whichever sequence tab it belongs
      // to, as soon as a mouse press lands anywhere outside that table (and outside its own
      // "Patterns" popup, so picking a pattern still works) - JTable's own focus-based
      // commit-on-blur doesn't reliably fire for clicks that land in Burp's own UI outside this
      // extension's panel.
      private AWTEventListener mouseEventListener = event -> {
        if (event instanceof MouseEvent mouseEvent && mouseEvent.getID() == MouseEvent.MOUSE_PRESSED
            && mouseEvent.getSource() instanceof Component source
            && SwingUtilities.getAncestorOfClass(JPopupMenu.class, source) == null) {
          for (var tab : EclipseScoutExtension.this.sequenceTabs) {
            if (!SwingUtilities.isDescendingFrom(source, tab.getVariableTableComponent())) {
              try {
                tab.commitVariableTableEdits();
              } catch (Exception exc) {
                EclipseScoutExtension.api.logging().logToError(exc);
              }
            }
          }
        }
      };

      @Override
      public void componentShown(ComponentEvent event) {
        KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(this.keyEventDispatcher);
        Toolkit.getDefaultToolkit().addAWTEventListener(this.mouseEventListener, AWTEvent.MOUSE_EVENT_MASK);
      }

      @Override
      public void componentHidden(ComponentEvent event) {
        KeyboardFocusManager.getCurrentKeyboardFocusManager().removeKeyEventDispatcher(this.keyEventDispatcher);
        Toolkit.getDefaultToolkit().removeAWTEventListener(this.mouseEventListener);
      }
    });
  }

  /**
   * Creates a new sequence named {@code name}, adds it as a tab (with a rename-on-double-click
   * header), selects it, and returns it. Used by "New sequence" in {@link #provideMenuItems} and
   * by {@link #restoreState}.
   */
  private EclipseScoutSequenceTab createSequenceTab(String name) {
    var tab = new EclipseScoutSequenceTab(this, name);
    this.sequenceTabs.add(tab);
    this.tabs.addTab(name, tab.getPanel());
    var index = this.tabs.indexOfComponent(tab.getPanel());
    this.tabs.setTabComponentAt(index, this.sequenceTabHeader(tab));
    this.tabs.setSelectedComponent(tab.getPanel());
    return tab;
  }

  /**
   * A tab header showing {@code tab}'s name, double-click-to-rename. Renaming re-tags every row
   * already in the sequence (see {@link EclipseScoutTableModel#setName}) and rejects a name
   * that's blank or already used by another sequence, since {@link EclipseScoutExchange#SEQUENCE_HEADER}
   * relies on names being unique to route a request back to the right sequence.
   */
  private JComponent sequenceTabHeader(EclipseScoutSequenceTab tab) {
    var label = new JLabel(tab.getName());
    label.addMouseListener(new MouseAdapter() {
      @Override
      public void mouseClicked(MouseEvent event) {
        // A custom tab component owning its own MouseListener intercepts the click before it
        // ever reaches JTabbedPane's own tab-switching handling, so clicking this label has to
        // select the tab itself - otherwise every click here (rename dialog or not) would leave
        // whatever tab was already selected in place.
        EclipseScoutExtension.this.tabs.setSelectedComponent(tab.getPanel());
        if (event.getClickCount() != 2) {
          return;
        }
        var input = JOptionPane.showInputDialog(EclipseScoutExtension.this.panel, "Sequence name:",
            "Rename sequence", JOptionPane.PLAIN_MESSAGE, null, null, tab.getName());
        if (input == null) {
          return;
        }
        var newName = String.valueOf(input).strip();
        if (newName.isEmpty() || newName.equals(tab.getName())) {
          return;
        }
        var collides = EclipseScoutExtension.this.sequenceTabs.stream()
            .anyMatch(other -> other != tab && other.getName().equals(newName));
        if (collides) {
          JOptionPane.showMessageDialog(EclipseScoutExtension.this.panel,
              "A sequence named \"%s\" already exists.".formatted(newName), "Rename sequence",
              JOptionPane.WARNING_MESSAGE);
          return;
        }
        tab.rename(newName);
        label.setText(newName);
        var index = EclipseScoutExtension.this.tabs.indexOfComponent(tab.getPanel());
        if (index >= 0) {
          EclipseScoutExtension.this.tabs.setTitleAt(index, newName);
        }
      }
    });
    var close = new JButton("x");
    close.setMargin(new Insets(0, 4, 0, 4));
    close.setFocusable(false);
    close.setBorderPainted(false);
    close.setContentAreaFilled(false);
    close.addActionListener(event -> {
      var confirmed = JOptionPane.showConfirmDialog(EclipseScoutExtension.this.panel,
          "Remove sequence \"%s\" and its %d row(s)? This cannot be undone."
              .formatted(tab.getName(), tab.getTableModel().getRowCount()),
          "Remove sequence", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
      if (confirmed == JOptionPane.YES_OPTION) {
        this.removeSequenceTab(tab);
      }
    });

    var header = new JPanel(new MigLayout("insets 0, gap 4"));
    header.setOpaque(false);
    header.add(label);
    header.add(close);
    return header;
  }

  /** Removes {@code tab}'s tab and stops tracking it - see {@link #sequenceTabHeader}'s "x" button. */
  private void removeSequenceTab(EclipseScoutSequenceTab tab) {
    this.sequenceTabs.remove(tab);
    this.tabs.remove(tab.getPanel());
  }

  /** The first "Sequence N" name not already used by an existing sequence. */
  private String nextDefaultSequenceName() {
    for (int n = 1;; n++) {
      var candidate = "Sequence " + n;
      if (this.sequenceTabs.stream().noneMatch(tab -> tab.getName().equals(candidate))) {
        return candidate;
      }
    }
  }

  /**
   * Opens a new closable tab, titled {@code "<namePrefix> - Run N"}, showing a live, read-only
   * view of one "Send sequence" run - a table of every exchange in that run in send order, with
   * its own request/response editor pair mirroring the selected row - and selects it. Kept
   * entirely separate from the sequence tabs, so the original captured rows stay inspectable and
   * editable while a run is in progress or after it's finished. Returns a callback the caller
   * should invoke - from any thread - with the run's exchanges as they progress; each call
   * refreshes the tab's table on the EDT while preserving its current row selection, so watching
   * a row fill in with its response doesn't get knocked back to "nothing selected" on every step.
   */
  Consumer<List<EclipseScoutExchange>> openRunTab(String namePrefix) {
    var runTableModel = new EclipseScoutTableModel();
    var runTable = new JTable(runTableModel);
    runTable.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
    runTable.getColumnModel().getColumn(0).setPreferredWidth(50);
    runTable.getColumnModel().getColumn(1).setPreferredWidth(50);
    runTable.getColumnModel().getColumn(2).setPreferredWidth(50);
    runTable.getColumnModel().getColumn(3).setPreferredWidth(100);
    runTable.getColumnModel().getColumn(4).setPreferredWidth(200);
    runTable.getColumnModel().getColumn(5).setPreferredWidth(400);

    var runRequestEditor = EclipseScoutExtension.api.userInterface().createHttpRequestEditor(EditorOptions.READ_ONLY);
    var runResponseEditor = EclipseScoutExtension.api.userInterface().createHttpResponseEditor(EditorOptions.READ_ONLY);
    runTable.getSelectionModel().addListSelectionListener(event -> {
      if (!event.getValueIsAdjusting() && runTable.getSelectedRow() >= 0) {
        var modelIndex = runTable.convertRowIndexToModel(runTable.getSelectedRow());
        var exchange = runTableModel.getExchangeAt(modelIndex);
        runRequestEditor.setRequest(exchange.request());
        runResponseEditor.setResponse(exchange.response());
      }
    });

    var runLower = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, runRequestEditor.uiComponent(),
        runResponseEditor.uiComponent());
    runLower.setResizeWeight(0.5);
    var runSplit = new JSplitPane(JSplitPane.VERTICAL_SPLIT, new JScrollPane(runTable), runLower);

    var title = "%s - Run %d".formatted(namePrefix, ++this.runTabCount);
    this.tabs.addTab(title, runSplit);
    this.tabs.setTabComponentAt(this.tabs.indexOfComponent(runSplit), this.closableTabHeader(title, runSplit));
    this.tabs.setSelectedComponent(runSplit);

    return exchanges -> SwingUtilities.invokeLater(() -> {
      var selectedModelIndex = runTable.getSelectedRow() >= 0
          ? runTable.convertRowIndexToModel(runTable.getSelectedRow())
          : -1;
      runTableModel.setExchanges(exchanges);
      if (selectedModelIndex >= 0 && selectedModelIndex < runTableModel.getRowCount()) {
        runTable.setRowSelectionInterval(selectedModelIndex, selectedModelIndex);
      }
    });
  }

  /** A tab header showing {@code title} plus a small "x" button that removes {@code content}'s tab from {@link #tabs}. */
  private JComponent closableTabHeader(String title, Component content) {
    var header = new JPanel(new MigLayout("insets 0, gap 4"));
    header.setOpaque(false);
    header.add(new JLabel(title));
    var close = new JButton("x");
    close.setMargin(new Insets(0, 4, 0, 4));
    close.setFocusable(false);
    close.setBorderPainted(false);
    close.setContentAreaFilled(false);
    close.addActionListener(event -> this.tabs.remove(content));
    header.add(close);
    return header;
  }

  /** This extension's suite tab component, as registered in {@link #initialize}. */
  public Component getComponent() {
    return this.panel;
  }

  /**
   * Saves every sequence - its name, rows and variable declarations - not the live "Run N" tabs,
   * which are just transient views of past sends - into the Burp project, so they're all there
   * again next time this project is opened. [Professional only] With no project file open
   * (Community Edition, or Pro run without one), {@link burp.api.montoya.persistence.Persistence}
   * falls back to storing this only in memory, so it survives the extension being reloaded but
   * not Burp being restarted. Registered as this extension's unloading handler in
   * {@link #initialize}, so it runs once, when the extension unloads - not on every edit.
   */
  private void persistState() {
    var sequenceNames = PersistedList.<String>persistedStringList();
    var requestResponses = PersistedList.<HttpRequestResponse>persistedHttpRequestResponseList();
    var exchangeNrs = PersistedList.<Integer>persistedIntegerList();
    var exchangeSequenceIndex = PersistedList.<Integer>persistedIntegerList();
    var variableExchangeNrs = PersistedList.<String>persistedStringList();
    var variableNames = PersistedList.<String>persistedStringList();
    var variableRegexes = PersistedList.<String>persistedStringList();
    var variableSequenceIndex = PersistedList.<Integer>persistedIntegerList();

    for (int seqIndex = 0; seqIndex < this.sequenceTabs.size(); seqIndex++) {
      var tab = this.sequenceTabs.get(seqIndex);
      sequenceNames.add(tab.getName());
      for (var exchange : tab.getTableModel().getExchanges()) {
        requestResponses.add(HttpRequestResponse.httpRequestResponse(exchange.request(), exchange.response()));
        exchangeNrs.add(exchange.nr());
        exchangeSequenceIndex.add(seqIndex);
      }
      for (var variable : tab.getVariableTableModel().getVariables()) {
        variableExchangeNrs.add(variable.exchangeNr() != null ? variable.exchangeNr().toString() : "");
        variableNames.add(variable.name());
        variableRegexes.add(variable.regex());
        variableSequenceIndex.add(seqIndex);
      }
    }

    var data = EclipseScoutExtension.api.persistence().extensionData();
    data.setStringList(PERSIST_KEY_SEQUENCE_NAMES, sequenceNames);
    data.setHttpRequestResponseList(PERSIST_KEY_EXCHANGES, requestResponses);
    data.setIntegerList(PERSIST_KEY_EXCHANGE_NRS, exchangeNrs);
    data.setIntegerList(PERSIST_KEY_EXCHANGE_SEQUENCE_INDEX, exchangeSequenceIndex);
    data.setStringList(PERSIST_KEY_VARIABLE_EXCHANGE_NRS, variableExchangeNrs);
    data.setStringList(PERSIST_KEY_VARIABLE_NAMES, variableNames);
    data.setStringList(PERSIST_KEY_VARIABLE_REGEXES, variableRegexes);
    data.setIntegerList(PERSIST_KEY_VARIABLE_SEQUENCE_INDEX, variableSequenceIndex);
  }

  /** Restores whatever {@link #persistState} last saved for this project, if anything. Called once from {@link #initialize}. */
  private void restoreState() {
    var data = EclipseScoutExtension.api.persistence().extensionData();

    var sequenceNames = data.getStringList(PERSIST_KEY_SEQUENCE_NAMES);
    if (sequenceNames == null) {
      return;
    }
    var tabs = new ArrayList<EclipseScoutSequenceTab>();
    for (var name : sequenceNames) {
      tabs.add(this.createSequenceTab(name));
    }

    // Restoring exchanges and variables are wrapped independently, not just this whole method
    // from the outside (see the try/catch around the call in initialize): a corrupt/unexpected
    // entry in one must not also cost the other, or every other already-restored sequence's rows.
    try {
      var requestResponses = data.getHttpRequestResponseList(PERSIST_KEY_EXCHANGES);
      var exchangeNrs = data.getIntegerList(PERSIST_KEY_EXCHANGE_NRS);
      var exchangeSequenceIndex = data.getIntegerList(PERSIST_KEY_EXCHANGE_SEQUENCE_INDEX);
      if (requestResponses != null && exchangeNrs != null && exchangeSequenceIndex != null) {
        var exchangesBySequence = new HashMap<Integer, List<EclipseScoutExchange>>();
        for (int i = 0; i < requestResponses.size(); i++) {
          var exchange = new EclipseScoutExchange(requestResponses.get(i));
          exchange.setNr(exchangeNrs.get(i));
          exchangesBySequence.computeIfAbsent(exchangeSequenceIndex.get(i), key -> new ArrayList<>()).add(exchange);
        }
        exchangesBySequence.forEach(
            (seqIndex, exchanges) -> tabs.get(seqIndex).getTableModel().setExchanges(exchanges));
      }
    } catch (Exception exc) {
      EclipseScoutExtension.api.logging().logToError("Failed to restore previously saved sequence rows");
      EclipseScoutExtension.api.logging().logToError(exc);
    }

    try {
      var variableExchangeNrs = data.getStringList(PERSIST_KEY_VARIABLE_EXCHANGE_NRS);
      var variableNames = data.getStringList(PERSIST_KEY_VARIABLE_NAMES);
      var variableRegexes = data.getStringList(PERSIST_KEY_VARIABLE_REGEXES);
      var variableSequenceIndex = data.getIntegerList(PERSIST_KEY_VARIABLE_SEQUENCE_INDEX);
      if (variableExchangeNrs != null && variableNames != null && variableRegexes != null
          && variableSequenceIndex != null) {
        var variablesBySequence = new HashMap<Integer, List<EclipseScoutVariable>>();
        for (int i = 0; i < variableExchangeNrs.size(); i++) {
          var exchangeNr = variableExchangeNrs.get(i).isEmpty() ? null : Integer.valueOf(variableExchangeNrs.get(i));
          variablesBySequence.computeIfAbsent(variableSequenceIndex.get(i), key -> new ArrayList<>())
              .add(new EclipseScoutVariable(exchangeNr, variableNames.get(i), variableRegexes.get(i)));
        }
        variablesBySequence.forEach((seqIndex, variables) -> tabs.get(seqIndex).getVariableTableModel()
            .setVariables(variables));
      }
    } catch (Exception exc) {
      EclipseScoutExtension.api.logging().logToError("Failed to restore previously saved variables");
      EclipseScoutExtension.api.logging().logToError(exc);
    }
  }

  /** Builds a menu item labeled {@code label} that runs {@code action} when clicked. */
  static JMenuItem menuItem(String label, Runnable action) {
    var item = new JMenuItem(label);
    item.addActionListener(event -> action.run());
    return item;
  }

  /**
   * Adds an "Eclipse Scout" submenu: "New sequence" (creates a new, empty sequence and adds the
   * selected request/response(s) to it), then one item per existing sequence (adds to that one
   * instead) - plus, when invoked from within one of this extension's own request editor panes,
   * that sequence's "Variables" submenu, which inserts a {@code $VAR:name$} placeholder for a
   * declared variable at the current cursor position.
   */
  @Override
  public List<Component> provideMenuItems(ContextMenuEvent event) {
    var items = new ArrayList<Component>();

    var sendToMenu = new JMenu(EclipseScoutExtension.NAME);
    sendToMenu.add(menuItem("New sequence", () -> {
      var tab = this.createSequenceTab(this.nextDefaultSequenceName());
      event.selectedRequestResponses().forEach(tab::addRequestResponse);
    }));
    if (!this.sequenceTabs.isEmpty()) {
      sendToMenu.addSeparator();
      for (var tab : this.sequenceTabs) {
        sendToMenu.add(menuItem(tab.getName(), () -> event.selectedRequestResponses().forEach(tab::addRequestResponse)));
      }
    }
    items.add(sendToMenu);

    this.sequenceTabs.stream()
        .filter(tab -> tab.isOwnRequestEditor(event))
        .findFirst()
        .flatMap(EclipseScoutSequenceTab::buildVariablesMenu)
        .ifPresent(items::add);

    return items;
  }

  /**
   * Intercepts a request tagged with {@link EclipseScoutExchange#REQUEST_HEADER} - sent to
   * Repeater/Intruder, or edited live and resent from there - and, before it goes out, uses
   * {@link EclipseScoutExchange#SEQUENCE_HEADER} to find which sequence it belongs to, replays
   * every earlier row of a fresh run of that sequence up to that row's position so its session
   * state and ids are established, then substitutes the tagged request in as that row (applying
   * the same uiSessionId propagation, renumbering, id remapping and variable substitution),
   * tracking the resulting {@link PendingSequence} so {@link #handleHttpResponseReceived} can
   * continue replaying once its response arrives.
   */
  @Override
  public RequestToBeSentAction handleHttpRequestToBeSent(HttpRequestToBeSent requestToBeSent) {
    try {
      if (requestToBeSent.hasHeader(EclipseScoutExchange.REQUEST_HEADER)) {
        var nr = Integer.parseInt(requestToBeSent.headerValue(EclipseScoutExchange.REQUEST_HEADER));
        var sequenceName = requestToBeSent.hasHeader(EclipseScoutExchange.SEQUENCE_HEADER)
            ? requestToBeSent.headerValue(EclipseScoutExchange.SEQUENCE_HEADER)
            : null;
        var showNr = requestToBeSent.hasHeader(EclipseScoutExchange.RESPONSE_HEADER)
            ? Integer.parseInt(requestToBeSent.headerValue(EclipseScoutExchange.RESPONSE_HEADER))
            : null;
        var request = requestToBeSent.withRemovedHeader(EclipseScoutExchange.REQUEST_HEADER)
            .withRemovedHeader(EclipseScoutExchange.RESPONSE_HEADER)
            .withRemovedHeader(EclipseScoutExchange.SEQUENCE_HEADER);

        var tab = sequenceName == null ? null
            : this.sequenceTabs.stream().filter(t -> t.getName().equals(sequenceName)).findFirst().orElse(null);
        if (tab == null) {
          EclipseScoutExtension.api.logging()
              .logToError("No sequence named \"%s\" found, sending as-is".formatted(sequenceName));
          return RequestToBeSentAction.continueWith(request);
        }

        var sequence = new EclipseScoutSequence(tab.getRequestResponsesAsDisplayed(),
            tab.getVariableTableModel().getVariables());
        var targetIndex = sequence.indexOfNr(nr);
        if (targetIndex < 0) {
          EclipseScoutExtension.api.logging().logToError("No row with # %d found, sending as-is".formatted(nr));
          return RequestToBeSentAction.continueWith(request);
        }
        var targetExchange = sequence.advanceTo(targetIndex, request);
        this.sequences.put(requestToBeSent.messageId(), new PendingSequence(tab, sequence, targetExchange, showNr));
        return RequestToBeSentAction.continueWith(targetExchange.request());
      }
    } catch (Exception exc) {
      EclipseScoutExtension.api.logging().logToError(exc);
    }
    return RequestToBeSentAction.continueWith(requestToBeSent);
  }

  /**
   * Completes a {@link PendingSequence} started by {@link #handleHttpRequestToBeSent}: records
   * the just-received response onto its target exchange, then replays the rest of the sequence
   * to the end. If the originating request was tagged with
   * {@link EclipseScoutExchange#RESPONSE_HEADER}, substitutes that row's own response in place of
   * the one actually received, so Repeater/Intruder displays what that row returned rather than
   * the response to whichever row was actually sent.
   *
   * <p>Independently of all that, every response actually seen in Proxy - not just ones this
   * extension is replaying - is checked for Scout's "startupData" marker (see
   * {@link EclipseScoutExchange#isStartupResponse}) and highlighted if it's there, so the request
   * that starts a new Scout UI session stands out in Proxy history without having to go looking
   * for it.
   */
  @Override
  public ResponseReceivedAction handleHttpResponseReceived(HttpResponseReceived responseReceived) {
    if (responseReceived.toolSource().isFromTool(ToolType.PROXY)
        && EclipseScoutExchange.isStartupResponse(responseReceived)) {
      var annotations = responseReceived.annotations();
      annotations.setHighlightColor(HighlightColor.GRAY);
      if (!annotations.hasNotes()) {
        annotations.setNotes("Eclipse Scout startup request");
      }
    }

    var pending = this.sequences.remove(responseReceived.messageId());
    if (pending != null) {
      pending.targetExchange().setResponse(responseReceived);
      pending.tab().beginRun();
      try {
        while (pending.sequence().hasNext() && !pending.tab().isCancelRequested()) {
          pending.sequence().sendNext();
        }
      } finally {
        pending.tab().endRun();
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
