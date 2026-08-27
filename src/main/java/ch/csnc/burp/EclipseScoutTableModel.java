package ch.csnc.burp;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

import javax.swing.table.AbstractTableModel;

import burp.api.montoya.http.message.HttpRequestResponse;

/**
 * Backs the main sequence table: one row per captured exchange, in display order, showing its
 * static "#", the "#" captured in its request/response bodies, and basic request info.
 */
public class EclipseScoutTableModel extends AbstractTableModel {

  private String[] columnNames = new String[] { "#", "Req#", "Res#", "Method", "Host", "Path" };
  private List<EclipseScoutExchange> exchanges = new ArrayList<>();

  /**
   * The static "#" the next row added via {@link #addExchange}/{@link #addRequestResponse} will
   * get - a monotonically increasing counter, never derived from the current row count, so a
   * deleted row's "#" is never handed out again to some unrelated later row. Reusing "#"s that
   * way would silently rebind anything still referencing the old row - e.g. a variable's
   * {@link EclipseScoutVariable#exchangeNr()} - to whatever new row happened to land on the same
   * number.
   */
  private int nextNr = 1;

  /**
   * The sequence this table belongs to, tagged onto every row's request via
   * {@link EclipseScoutExchange#setSequenceName} so {@code EclipseScoutExtension} can route a
   * request coming back from Repeater/Intruder to the right sequence - or {@code null} for an
   * anonymous, throwaway table (e.g. a "Run N" tab's live view), which never needs that tagging.
   */
  private String name;

  /** Creates an empty, anonymous table model with no captured exchanges yet - see {@link #name}. */
  public EclipseScoutTableModel() {
    this(null);
  }

  /** Creates an empty table model for the sequence named {@code name} - see {@link #name}. */
  public EclipseScoutTableModel(String name) {
    this.name = name;
  }

  /** This sequence's display name, or {@code null} for an anonymous table - see {@link #name}. */
  public String getName() {
    return this.name;
  }

  /**
   * Renames this sequence, re-tagging every existing row's request with the new name so a
   * request already sent to Repeater/Intruder before the rename still routes back correctly.
   */
  public void setName(String name) {
    this.name = name;
    this.exchanges.forEach(exchange -> exchange.setSequenceName(name));
  }

  @Override
  public int getRowCount() {
    return this.exchanges.size();
  }

  @Override
  public int getColumnCount() {
    return this.columnNames.length;
  }

  @Override
  public String getColumnName(int col) {
    return this.columnNames[col];
  }

  /** Renders the given column of the row's exchange, blanking any "#"/"Req#"/"Res#" that is unset. */
  @Override
  public Object getValueAt(int rowIndex, int columnIndex) {
    var exchange = this.exchanges.get(rowIndex);
    var request = exchange.request();
    switch (columnIndex) {
      case 0:
        return exchange.nr() != null ? exchange.nr() : "";
      case 1:
        return exchange.sequenceNrRequest() != null ? exchange.sequenceNrRequest() : "";
      case 2:
        return exchange.sequenceNrResponse() != null ? exchange.sequenceNrResponse() : "";
      case 3:
        return request.method();
      case 4:
        return request.headerValue("Host");
      case 5:
        return request.path();
      default:
        return null;
    }
  }

  /** Reorders rows to reflect a drag-and-drop move, e.g. via {@link EclipseScoutTableRowTransferHandler}. */
  public void moveRow(int from, int to) {
    if (from == to) {
      return;
    }
    var exchange = this.exchanges.remove(from);
    this.exchanges.add(to, exchange);
    this.fireTableDataChanged();
  }

  /** Sorts rows by the "#", "Req#" or "Res#" column; clicking any other column header is a no-op. */
  public void sortByColumn(int column) {
    switch (column) {
      case 0:
        this.exchanges.sort(Comparator.comparing(EclipseScoutExchange::nr, Comparator.nullsFirst(Comparator.naturalOrder())));
        break;
      case 1:
        this.exchanges.sort(Comparator.comparing(EclipseScoutExchange::sequenceNrRequest, Comparator.nullsFirst(Comparator.naturalOrder())));
        break;
      case 2:
        this.exchanges.sort(Comparator.comparing(EclipseScoutExchange::sequenceNrResponse, Comparator.nullsFirst(Comparator.naturalOrder())));
        break;
    }
    this.fireTableDataChanged();
  }

  /** All rows' exchanges, in display order - the live backing list, not a copy. */
  public List<EclipseScoutExchange> getExchanges() {
    return this.exchanges;
  }

  /** The exchange backing the row at model index {@code row}. */
  public EclipseScoutExchange getExchangeAt(int row) {
    return this.getExchanges().get(row);
  }

  /**
   * Replaces the row's exchange with one built from {@code requestResponse}, e.g. after editing
   * it live in the request/response editors, while preserving the row's original static "#" so
   * it stays addressable by {@link EclipseScoutSequence}.
   */
  public void setRequestResponseAt(int row, HttpRequestResponse requestResponse) {
    var nr = this.exchanges.get(row).nr();
    var exchange = new EclipseScoutExchange(requestResponse);
    exchange.setNr(nr);
    if (this.name != null) {
      exchange.setSequenceName(this.name);
    }
    this.setExchangeAt(row, exchange);
  }

  /** Overwrites the row at model index {@code row} with {@code exchange}, discarding its own static "#". */
  public void setExchangeAt(int row, EclipseScoutExchange exchange) {
    this.exchanges.set(row, exchange);
    this.fireTableRowsUpdated(row, row);
  }

  /** Wraps {@code requestResponse} in a fresh exchange and appends it as a new row; see {@link #addExchange}. */
  public void addRequestResponse(HttpRequestResponse requestResponse) {
    this.addExchange(new EclipseScoutExchange(requestResponse));
  }

  /** Appends {@code exchange} as a new last row, assigning it the next static "#" - see {@link #nextNr}. */
  public void addExchange(EclipseScoutExchange exchange) {
    exchange.setNr(this.nextNr++);
    if (this.name != null) {
      exchange.setSequenceName(this.name);
    }
    this.exchanges.add(exchange);
    this.fireTableDataChanged();
  }

  /** Deletes the row backed by {@code exchange}, if it is still present. */
  public void removeRequestResponse(EclipseScoutExchange exchange) {
    this.exchanges.remove(exchange);
    this.fireTableDataChanged();
  }

  /**
   * Replaces all rows with independent clones of {@code exchanges}, in the given order, and
   * advances {@link #nextNr} past the highest "#" among them if it isn't already, so a row added
   * afterward can't collide with one just loaded - e.g. when restoring previously persisted rows.
   */
  public void setExchanges(List<EclipseScoutExchange> exchanges) {
    this.exchanges = new ArrayList<>(exchanges.stream().map(EclipseScoutExchange::clone).toList());
    if (this.name != null) {
      this.exchanges.forEach(exchange -> exchange.setSequenceName(this.name));
    }
    this.exchanges.stream().map(EclipseScoutExchange::nr).filter(Objects::nonNull)
        .mapToInt(Integer::intValue).max()
        .ifPresent(maxNr -> this.nextNr = Math.max(this.nextNr, maxNr + 1));
    this.fireTableDataChanged();
  }

}
