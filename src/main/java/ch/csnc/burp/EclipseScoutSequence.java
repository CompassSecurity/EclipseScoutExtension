package ch.csnc.burp;

import java.util.List;

public class EclipseScoutSequence {

  private int position = 0;

  private List<EclipseScoutExchange> exchanges;

  public EclipseScoutSequence(List<EclipseScoutExchange> exchanges) {
    this.exchanges = exchanges.stream().map(EclipseScoutExchange::clone).toList();
  }

  public boolean hasNext() {
    return this.position < this.exchanges.size();
  }

  /**
   * Returns the next exchange in display order, propagating the UI session id from the
   * previous exchange and renumbering its body's "#" sequence number to match its position,
   * so that replayed exchanges stay contiguous regardless of their originally captured numbers.
   */
  public EclipseScoutExchange next() {
    var exchange = this.exchanges.get(this.position);
    if (this.position > 0) {
      exchange.setUiSessionId(this.exchanges.get(this.position - 1).getUiSessionId());
    }
    exchange.renumberSequenceNr(this.position);
    this.position++;
    return exchange;
  }

  /**
   * Finds the position of the exchange with the given static {@code nr} in display order,
   * or -1 if no such exchange exists.
   */
  public int indexOfNr(int nr) {
    for (int i = 0; i < this.exchanges.size(); i++) {
      var exchangeNr = this.exchanges.get(i).nr();
      if (exchangeNr != null && exchangeNr == nr) {
        return i;
      }
    }
    return -1;
  }

  /**
   * Sends every exchange strictly before {@code index} (so their responses/session state
   * are established), then returns the exchange at {@code index} without sending it -
   * that one is expected to be sent by the caller instead.
   */
  public EclipseScoutExchange advanceTo(int index) {
    while (this.position < index) {
      this.next().send();
    }
    return this.next();
  }

}
