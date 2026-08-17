package ch.csnc.burp;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import javax.swing.table.AbstractTableModel;
import javax.swing.table.TableRowSorter;

import burp.api.montoya.http.message.HttpRequestResponse;

public class EclipseScoutTableModel extends AbstractTableModel {

  private String[] columnNames = new String[] { "Req#", "Res#", "Method", "Host", "Path" };
  private List<EclipseScoutExchange> requestResponses = new ArrayList<>();
  private TableRowSorter<EclipseScoutTableModel> tableRowSorter = new TableRowSorter<>(this);

  public EclipseScoutTableModel() {
    Comparator<?> seqNrComparator = Comparator.nullsFirst((s1, s2) -> {
      int n1, n2;
      try {
        n1 = Integer.parseInt(s1.toString());
      } catch (NumberFormatException e) {
        n1 = Integer.MIN_VALUE; // invalid strings first
      }
      try {
        n2 = Integer.parseInt(s2.toString());
      } catch (NumberFormatException e) {
        n2 = Integer.MAX_VALUE;
      }

      return Integer.compare(n1, n2);
    });
    this.tableRowSorter.setComparator(0, seqNrComparator);
    this.tableRowSorter.setComparator(1, seqNrComparator);
  }

  @Override
  public int getRowCount() {
    return this.requestResponses.size();
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
    var requestResponse = this.requestResponses.get(rowIndex);
    var request = requestResponse.request();
    switch (columnIndex) {
      case 0:
        return requestResponse.sequenceNrRequest() != null ? requestResponse.sequenceNrRequest() : "";
      case 1:
        return requestResponse.sequenceNrResponse() != null ? requestResponse.sequenceNrResponse() : "";
      case 2:
        return request.method();
      case 3:
        return request.headerValue("Host");
      case 4:
        return request.path();
      default:
        return null;
    }
  }

  public List<EclipseScoutExchange> getRequestResponses() {
    return this.requestResponses;
  }

  public EclipseScoutExchange getRequestResponseAt(int row) {
    return this.getRequestResponses().get(row);
  }

  public void setRequestResponseAt(int row, HttpRequestResponse requestResponse) {
    this.setRequestResponseAt(row, new EclipseScoutExchange(requestResponse));
  }

  public void setRequestResponseAt(int row, EclipseScoutExchange requestResponse) {
    this.requestResponses.set(row, requestResponse);
    this.fireTableRowsUpdated(row, row);
  }

  public void addRequestResponse(HttpRequestResponse requestResponse) {
    this.addRequestResponse(new EclipseScoutExchange(requestResponse));
  }

  public void addRequestResponse(EclipseScoutExchange requestResponse) {
    this.requestResponses.add(requestResponse);
    this.fireTableDataChanged();
  }

  public void removeRequestResponse(EclipseScoutExchange requestResponse) {
    this.requestResponses.remove(requestResponse);
    this.fireTableDataChanged();
  }

  public void setRequestResponses(List<EclipseScoutExchange> requestResponses) {
    this.requestResponses = new ArrayList<>(
        requestResponses.stream().map(EclipseScoutExchange::clone).toList());
    this.fireTableDataChanged();
  }

  public TableRowSorter<EclipseScoutTableModel> getTableRowSorter() {
    return this.tableRowSorter;
  }

}
