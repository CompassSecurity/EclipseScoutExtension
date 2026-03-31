package ch.csnc.burp;

import java.util.List;

import burp.api.montoya.http.handler.HttpRequestToBeSent;
import burp.api.montoya.http.handler.HttpResponseReceived;
import burp.api.montoya.http.handler.RequestToBeSentAction;
import burp.api.montoya.http.handler.ResponseReceivedAction;

public class EclipseScoutSequence {

  private List<EclipseScoutHttpRequestResponse> requestResponses;

  public EclipseScoutSequence(List<EclipseScoutHttpRequestResponse> requestResponses) {
    this.requestResponses = requestResponses.stream().map(EclipseScoutHttpRequestResponse::clone).toList();
  }

  public List<EclipseScoutHttpRequestResponse> getHttpRequestResponses() {
    return this.requestResponses;
  }

  public RequestToBeSentAction continueWith(HttpRequestToBeSent requestToBeSent) {
    throw new UnsupportedOperationException();
  }

  public ResponseReceivedAction continueWith(HttpResponseReceived responseReceived) {
    throw new UnsupportedOperationException();
  }

  public void send() {
    for (var i = 0; i < this.requestResponses.size(); i++) {
      String uiSessionId = null;
      if (i > 0) {
        uiSessionId = this.requestResponses.get(i-1).getUiSessionId();
      }
      var requestResponse = this.requestResponses.get(i);
      requestResponse.setUiSessionId(uiSessionId);
      var response = EclipseScoutExtension.api.http().sendRequest(requestResponse.request()).response();
      requestResponse.setResponse(response);
    }
  }
  
}
