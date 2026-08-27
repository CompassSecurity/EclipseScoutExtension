package ch.csnc.burp;

public class EclipseScoutVariable {

  private Integer exchangeNr;
  private String name;
  private String regex;

  /** Creates a variable with no exchange assigned and an empty name/regex - the state of a newly added row. */
  public EclipseScoutVariable() {
    this(null, "", "");
  }

  /** Creates a variable already targeting {@code exchangeNr} with the given name and regex. */
  public EclipseScoutVariable(Integer exchangeNr, String name, String regex) {
    this.exchangeNr = exchangeNr;
    this.name = name;
    this.regex = regex;
  }

  /** The static "#" of the one exchange this variable is extracted from - see {@link EclipseScoutExchange#nr()}. */
  public Integer exchangeNr() {
    return this.exchangeNr;
  }

  /** Retargets this variable to extract from a different exchange, identified by its static "#". */
  public void setExchangeNr(Integer exchangeNr) {
    this.exchangeNr = exchangeNr;
  }

  /** This variable's name, referenced elsewhere in the sequence via {@code $VAR:name$} placeholders. */
  public String name() {
    return this.name;
  }

  /** Renames this variable; placeholders already substituted with the old name are unaffected. */
  public void setName(String name) {
    this.name = name;
  }

  /** The regex run against the target exchange's response body to extract this variable's value. */
  public String regex() {
    return this.regex;
  }

  /** Changes the regex used to extract this variable's value; see {@link #regex()}. */
  public void setRegex(String regex) {
    this.regex = regex;
  }

}
