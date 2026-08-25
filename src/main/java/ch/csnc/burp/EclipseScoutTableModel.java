package ch.csnc.burp;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import javax.swing.table.AbstractTableModel;

import burp.api.montoya.http.message.HttpRequestResponse;
import burp.api.montoya.http.message.responses.HttpResponse;

/**
 * Backs the main sequence table: one row per captured exchange, in display order, showing its
 * static "#", the "#" captured in its request/response bodies, and basic request info.
 */
public class EclipseScoutTableModel extends AbstractTableModel {

  private String[] columnNames = new String[] { "#", "Req#", "Res#", "Method", "Host", "Path" };
  private List<EclipseScoutExchange> exchanges = new ArrayList<>();

  public EclipseScoutTableModel() {
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

  /**
   * Updates the response of the row whose exchange has the given static {@code nr}, if any -
   * a no-op if no row has that "#". Used to reflect what a "Send sequence" replay actually
   * returned without touching the row's own captured request, which must stay pristine for
   * {@link EclipseScoutSequence}'s per-run id remapping and renumbering on later replays.
   */
  public void setResponseForNr(int nr, HttpResponse response) {
    for (int i = 0; i < this.exchanges.size(); i++) {
      var exchange = this.exchanges.get(i);
      if (exchange.nr() != null && exchange.nr() == nr) {
        exchange.setResponse(response);
        this.fireTableRowsUpdated(i, i);
        return;
      }
    }
  }

  public List<EclipseScoutExchange> getExchanges() {
    return this.exchanges;
  }

  public EclipseScoutExchange getExchangeAt(int row) {
    return this.getExchanges().get(row);
  }

  public void setRequestResponseAt(int row, HttpRequestResponse requestResponse) {
    var nr = this.exchanges.get(row).nr();
    var exchange = new EclipseScoutExchange(requestResponse);
    exchange.setNr(nr);
    this.setExchangeAt(row, exchange);
  }

  public void setExchangeAt(int row, EclipseScoutExchange exchange) {
    this.exchanges.set(row, exchange);
    this.fireTableRowsUpdated(row, row);
  }

  public void addRequestResponse(HttpRequestResponse requestResponse) {
    this.addExchange(new EclipseScoutExchange(requestResponse));
  }

  public void addExchange(EclipseScoutExchange exchange) {
    exchange.setNr(this.exchanges.size() + 1);
    this.exchanges.add(exchange);
    this.fireTableDataChanged();
  }

  public void removeRequestResponse(EclipseScoutExchange exchange) {
    this.exchanges.remove(exchange);
    this.fireTableDataChanged();
  }

  public void setExchanges(List<EclipseScoutExchange> exchanges) {
    this.exchanges = new ArrayList<>(exchanges.stream().map(EclipseScoutExchange::clone).toList());
    this.fireTableDataChanged();
  }

}
