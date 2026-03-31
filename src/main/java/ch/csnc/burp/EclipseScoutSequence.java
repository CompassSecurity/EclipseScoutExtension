package ch.csnc.burp;

import java.util.List;

public class EclipseScoutSequence {

  private int position = 0;

  private List<EclipseScoutHttpRequestResponse> requestResponses;

  public EclipseScoutSequence(List<EclipseScoutHttpRequestResponse> requestResponses) {
    this.requestResponses = requestResponses.stream().map(EclipseScoutHttpRequestResponse::clone).toList();
  }

  public boolean hasNext() {
    return this.position < this.requestResponses.size();
  }

  public EclipseScoutHttpRequestResponse next() {
    try {
      String uiSessionId = null;
      if (this.position > 0) {
        uiSessionId = this.requestResponses.get(this.position-1).getUiSessionId();
      }
      return this.requestResponses.get(position).withUiSessionId(uiSessionId);
    } finally {
      position++;
    }
  }
 
}
