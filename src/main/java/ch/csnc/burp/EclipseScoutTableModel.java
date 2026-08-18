package ch.csnc.burp;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import javax.swing.table.AbstractTableModel;

import burp.api.montoya.http.message.HttpRequestResponse;

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

  public void moveRow(int from, int to) {
    if (from == to) {
      return;
    }
    var exchange = this.exchanges.remove(from);
    this.exchanges.add(to, exchange);
    this.fireTableDataChanged();
  }

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
