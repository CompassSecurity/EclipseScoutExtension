package ch.csnc.burp;

import org.json.JSONException;
import org.json.JSONObject;

import burp.api.montoya.http.message.ContentType;
import burp.api.montoya.http.message.HttpRequestResponse;
import burp.api.montoya.http.message.requests.HttpRequest;
import burp.api.montoya.http.message.responses.HttpResponse;

public class EclipseScoutHttpRequestResponse {

  private HttpRequest request;
  private HttpResponse response;

  private Integer sequenceNr;
  private String uiSessionId;

  public EclipseScoutHttpRequestResponse(HttpRequest request) {
    this(HttpRequestResponse.httpRequestResponse(request, null));
  }

  public EclipseScoutHttpRequestResponse(HttpRequestResponse requestResponse) {
    this.request = HttpRequest.httpRequest(requestResponse.request().httpService(), requestResponse.request().toByteArray());
    if (requestResponse.response() != null) {
      this.response = HttpResponse.httpResponse(requestResponse.response().toByteArray());
    }

    if (this.request.contentType() == ContentType.JSON) {
      try {
        var body = this.request.bodyToString().strip();
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
    return this.request;
  }

  public HttpResponse response() {
    return this.response;
  }

  public void setResponse(HttpResponse response) {
    this.response = response;
    try {
      var jsonObj = new JSONObject(response.bodyToString());
      var startupData = jsonObj.getJSONObject("startupData");
      this.uiSessionId = startupData.getString("uiSessionId");
    } catch (JSONException exc) {
      // ignore
    }
  }

  public HttpRequestResponse requestResponse() {
    return HttpRequestResponse.httpRequestResponse(this.request, this.response);
  }

  public Integer sequenceNr() {
    return this.sequenceNr;
  }

  public void setUiSessionId(String uiSessionId) {
    this.uiSessionId = uiSessionId;
    try {
      var jsonObj = new JSONObject(this.request.bodyToString());
      jsonObj.put("uiSessionId", uiSessionId);
      this.request = this.request.withBody(jsonObj.toString());
    } catch (JSONException exc) {
      // ignore
    }
  }

  public EclipseScoutHttpRequestResponse withUiSessionId(String uiSessionId) {
    this.setUiSessionId(uiSessionId);
    return this;
  }

  public String getUiSessionId() {
    return this.uiSessionId;
  }

  public EclipseScoutHttpRequestResponse clone() {
    return new EclipseScoutHttpRequestResponse(
      HttpRequestResponse.httpRequestResponse(
        HttpRequest.httpRequest(this.request().httpService(), this.request().toByteArray()),
        HttpResponse.httpResponse(this.response().toByteArray())));
  }

  public void send() {
    var response = EclipseScoutExtension.api.http().sendRequest(this.request).response();
    this.setResponse(response);
  }
  
}
