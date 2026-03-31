package ch.csnc.burp;

import java.util.ArrayList;
import java.util.List;

import javax.swing.table.AbstractTableModel;
import javax.swing.table.TableRowSorter;

import burp.api.montoya.http.message.HttpRequestResponse;

public class EclipseScoutTableModel extends AbstractTableModel {

  private String[] columnNames = new String[]{"#", "Method", "Host", "Path"};
  private List<EclipseScoutHttpRequestResponse> requestResponses = new ArrayList<>();
  private TableRowSorter<EclipseScoutTableModel> tableRowSorter = new TableRowSorter<>(this);

  public EclipseScoutTableModel() {
    this.tableRowSorter.setComparator(0, (s1, s2) -> {
      // Handle nulls first
      if (s1 == null && s2 == null) return 0;
      if (s1 == null) return 1;   // nulls last
      if (s2 == null) return -1;

      // Parse integers, handle invalid strings
      int n1, n2;
      try {
          n1 = Integer.parseInt(s1.toString());
      } catch (NumberFormatException e) {
          n1 = Integer.MAX_VALUE; // invalid strings last
      }
      try {
          n2 = Integer.parseInt(s2.toString());
      } catch (NumberFormatException e) {
          n2 = Integer.MAX_VALUE;
      }

      return Integer.compare(n1, n2);
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
	      return requestResponse.sequenceNr() != null ? requestResponse.sequenceNr() : "";
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
	  this.requestResponses.set(row, requestResponse);
	  this.fireTableRowsUpdated(row, row);
	}

	public void addRequestResponse(HttpRequestResponse requestResponse) {
	  this.addRequestResponse(new EclipseScoutHttpRequestResponse(requestResponse));
	}

	public void addRequestResponse(EclipseScoutHttpRequestResponse requestResponse) {
	  this.requestResponses.add(requestResponse);
	  this.fireTableDataChanged();
	}

	public void removeRequestResponse(EclipseScoutHttpRequestResponse requestResponse) {
	  this.requestResponses.remove(requestResponse);
	  this.fireTableDataChanged();
	}

	public void setRequestResponses(List<EclipseScoutHttpRequestResponse> requestResponses) {
	  this.requestResponses = new ArrayList<>(requestResponses.stream().map(EclipseScoutHttpRequestResponse::clone).toList());
	  this.fireTableDataChanged();
	}

	public TableRowSorter<EclipseScoutTableModel> getTableRowSorter() {
	  return this.tableRowSorter;
	}
  
}
