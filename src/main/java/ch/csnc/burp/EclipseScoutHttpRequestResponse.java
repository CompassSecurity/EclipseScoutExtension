package ch.csnc.burp;

import java.util.Optional;

import org.json.JSONException;
import org.json.JSONObject;

import burp.api.montoya.http.message.ContentType;
import burp.api.montoya.http.message.HttpRequestResponse;
import burp.api.montoya.http.message.requests.HttpRequest;
import burp.api.montoya.http.message.responses.HttpResponse;

public class EclipseScoutHttpRequestResponse {

  private HttpRequestResponse requestResponse;
  private Integer sequenceNr;

  public EclipseScoutHttpRequestResponse(HttpRequestResponse requestResponse) {
    this.requestResponse = requestResponse;

    var request = requestResponse.request();
    if (request.contentType() == ContentType.JSON) {
      try {
        var body = request.bodyToString().strip();
        if (body.startsWith("{")) {
          var jsonObj = new JSONObject(body);
          this.sequenceNr = jsonObj.has("#") ? jsonObj.getInt("#") : null;
        }
      } catch (JSONException exc) {
        this.sequenceNr = null;
      }
    }
  }

  public HttpRequest request() {
    return this.requestResponse.request();
  }

  public HttpResponse response() {
    return this.requestResponse.response();
  }

  public HttpRequestResponse requestResponse() {
    return this.requestResponse;
  }

  public Optional<Integer> sequenceNr() {
    return Optional.ofNullable(this.sequenceNr);
  }

  public void setUiSessionId(String uiSessionId) {
    var jsonObj = new JSONObject(this.requestResponse.request().bodyToString());
    jsonObj.put("uiSessionId", uiSessionId);
    var updatedRequest = this.requestResponse.request().withBody(jsonObj.toString());
    this.requestResponse = HttpRequestResponse.httpRequestResponse(updatedRequest, this.requestResponse.response());
  }
  
}
