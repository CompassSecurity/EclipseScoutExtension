package ch.csnc.burp;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import javax.swing.table.AbstractTableModel;
import javax.swing.table.TableRowSorter;

import burp.api.montoya.http.message.HttpRequestResponse;

public class EclipseScoutTableModel extends AbstractTableModel {

  private String[] columnNames = new String[]{"#", "Method", "Host", "Path"};
  private List<EclipseScoutHttpRequestResponse> requestResponses = Collections.synchronizedList(new ArrayList<>());
  private TableRowSorter<EclipseScoutTableModel> tableRowSorter = new TableRowSorter<>(this);

  public EclipseScoutTableModel() {
    this.tableRowSorter.setComparator(0, (s1, s2) -> {
      try {
        Integer i1 = Integer.parseInt((String) s1);
        Integer i2 = Integer.parseInt((String) s2);
        return i1.compareTo(i2);
      } catch (NumberFormatException e) {
          return -1;
      }
    });
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
	      return requestResponse.sequenceNr().map(String::valueOf).orElse("");
	    case 1:
	      return request.method();
	    case 2:
	      return request.headerValue("Host");
	    case 3:
	      return request.path();
	    default:
	      return null;
	  }
	}

	public List<EclipseScoutHttpRequestResponse> getRequestResponses() {
	  return this.requestResponses;
	}

	public EclipseScoutHttpRequestResponse getRequestResponseAt(int row) {
	  return this.getRequestResponses().get(row);
	}

	public void setRequestResponseAt(int row, HttpRequestResponse requestResponse) {
	  this.setRequestResponseAt(row, new EclipseScoutHttpRequestResponse(requestResponse));
	}

	public void setRequestResponseAt(int row, EclipseScoutHttpRequestResponse requestResponse) {
	  requestResponses.set(row, requestResponse);
	  this.fireTableRowsUpdated(row, row);
	}

	public void addRequestResponse(HttpRequestResponse requestResponse) {
	  this.addRequestResponse(new EclipseScoutHttpRequestResponse(requestResponse));
	}

	public void addRequestResponse(EclipseScoutHttpRequestResponse requestResponse) {
	  requestResponses.add(requestResponse);
	  this.fireTableDataChanged();
	}

	public void removeRequestResponse(EclipseScoutHttpRequestResponse requestResponse) {
	  this.requestResponses.remove(requestResponse);
	  this.fireTableDataChanged();
	}

	public TableRowSorter<EclipseScoutTableModel> getTableRowSorter() {
	  return this.tableRowSorter;
	}
  
}
