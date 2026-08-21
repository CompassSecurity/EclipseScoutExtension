package ch.csnc.burp;

public class EclipseScoutVariable {

  private Integer exchangeNr;
  private String name;
  private String regex;

  public EclipseScoutVariable() {
    this(null, "", "");
  }

  public EclipseScoutVariable(Integer exchangeNr, String name, String regex) {
    this.exchangeNr = exchangeNr;
    this.name = name;
    this.regex = regex;
  }

  /** The static "#" of the one exchange this variable is extracted from - see {@link EclipseScoutExchange#nr()}. */
  public Integer exchangeNr() {
    return this.exchangeNr;
  }

  public void setExchangeNr(Integer exchangeNr) {
    this.exchangeNr = exchangeNr;
  }

  public String name() {
    return this.name;
  }

  public void setName(String name) {
    this.name = name;
  }

  public String regex() {
    return this.regex;
  }

  public void setRegex(String regex) {
    this.regex = regex;
  }

}
