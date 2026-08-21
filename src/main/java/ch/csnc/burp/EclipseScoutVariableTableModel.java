package ch.csnc.burp;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import javax.swing.table.AbstractTableModel;

/**
 * Backs the variables table: each row declares which exchange (by its static "#") the variable
 * is extracted from, its name and its regex, plus a read-only "Preview" column showing what the
 * regex currently extracts from that exchange's response body - so the user can see the match
 * live while editing.
 */
public class EclipseScoutVariableTableModel extends AbstractTableModel {

  private final String[] columnNames = new String[] { "Exchange #", "Name", "Regex", "Preview" };
  private final List<EclipseScoutVariable> variables = new ArrayList<>();
  private final Function<Integer, EclipseScoutExchange> exchangeByNr;

  public EclipseScoutVariableTableModel(Function<Integer, EclipseScoutExchange> exchangeByNr) {
    this.exchangeByNr = exchangeByNr;
  }

  @Override
  public int getRowCount() {
    return this.variables.size();
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
  public boolean isCellEditable(int row, int col) {
    return col == 0 || col == 1 || col == 2;
  }

  @Override
  public Object getValueAt(int row, int col) {
    var variable = this.variables.get(row);
    return switch (col) {
      case 0 -> variable.exchangeNr() != null ? variable.exchangeNr() : "";
      case 1 -> variable.name();
      case 2 -> variable.regex();
      case 3 -> preview(variable);
      default -> null;
    };
  }

  @Override
  public void setValueAt(Object value, int row, int col) {
    var variable = this.variables.get(row);
    var text = String.valueOf(value).strip();
    switch (col) {
      case 0 -> {
        try {
          variable.setExchangeNr(text.isEmpty() ? null : Integer.valueOf(text));
        } catch (NumberFormatException exc) {
          // ignore unparsable input, keep previous value
        }
      }
      case 1 -> variable.setName(text);
      case 2 -> variable.setRegex(text);
      default -> {
      }
    }
    this.fireTableRowsUpdated(row, row);
  }

  private String preview(EclipseScoutVariable variable) {
    if (variable.exchangeNr() == null || variable.regex() == null || variable.regex().isBlank()) {
      return "";
    }
    var exchange = this.exchangeByNr.apply(variable.exchangeNr());
    if (exchange == null) {
      return "";
    }
    var body = exchange.responseBodyText();
    if (body == null) {
      return "";
    }
    try {
      var matcher = Pattern.compile(variable.regex()).matcher(body);
      if (!matcher.find()) {
        return "";
      }
      return matcher.groupCount() >= 1 ? matcher.group(1) : matcher.group(0);
    } catch (PatternSyntaxException exc) {
      return "invalid regex";
    }
  }

  public void addVariable(Integer exchangeNr) {
    this.variables.add(new EclipseScoutVariable(exchangeNr, "", ""));
    this.fireTableDataChanged();
  }

  public void removeVariables(List<EclipseScoutVariable> toRemove) {
    this.variables.removeAll(toRemove);
    this.fireTableDataChanged();
  }

  public EclipseScoutVariable getVariableAt(int row) {
    return this.variables.get(row);
  }

  public List<EclipseScoutVariable> getVariables() {
    return this.variables;
  }

  public void refreshPreview() {
    this.fireTableDataChanged();
  }

}
