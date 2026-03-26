package ch.csnc.burp;

import java.util.ArrayList;
import java.util.List;

import javax.swing.table.AbstractTableModel;

import burp.api.montoya.http.message.HttpRequestResponse;

public class EclipseScoutTableModel extends AbstractTableModel {

  private String[] columnNames = new String[]{"#", "Method", "Host", "Path"};
  private List<HttpRequestResponse> requestResponses = new ArrayList<>();

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
	      return rowIndex+1;
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

	public HttpRequestResponse getRequestResponseAt(int row) {
	  return this.requestResponses.get(row);
	}

	public void setRequestResponseAt(int row, HttpRequestResponse requestResponse) {
	  this.requestResponses.set(row, requestResponse);
	}

	public void addRequestResponse(HttpRequestResponse requestResponse) {
	  this.requestResponses.add(requestResponse);
	  this.fireTableDataChanged();
	}
  
}
