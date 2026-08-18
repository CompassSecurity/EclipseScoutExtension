package ch.csnc.burp;

import org.json.JSONException;
import org.json.JSONObject;

import burp.api.montoya.http.message.ContentType;
import burp.api.montoya.http.message.HttpRequestResponse;
import burp.api.montoya.http.message.requests.HttpRequest;
import burp.api.montoya.http.message.responses.HttpResponse;

public class EclipseScoutExchange {
  
  private HttpRequest request;
  private HttpResponse response;

  private Integer nr;
  private Integer sequenceNrRequest;
  private Integer sequenceNrResponse;
  private String uiSessionId;

  public EclipseScoutExchange(HttpRequest request) {
    this(HttpRequestResponse.httpRequestResponse(request, null));
  }

  public EclipseScoutExchange(HttpRequestResponse requestResponse) {
    this.request = HttpRequest.httpRequest(requestResponse.request().httpService(),
        requestResponse.request().toByteArray());
    if (requestResponse.response() != null) {
      this.response = HttpResponse.httpResponse(requestResponse.response().toByteArray());
    }

    if (this.request.contentType() == ContentType.JSON) {
      try {
        {
          var body = this.request.bodyToString().strip();
          if (body.startsWith("{")) {
            var jsonObj = new JSONObject(body);
            this.sequenceNrRequest = jsonObj.has("#") ? jsonObj.getInt("#") : null;
          }
        }
        {
          if (this.response != null) {
            var body = this.response.bodyToString().strip();
            if (body.startsWith("{")) {
              var jsonObj = new JSONObject(body);
              this.sequenceNrResponse = jsonObj.has("#") ? jsonObj.getInt("#") : null;
            }
          }
        }
      } catch (JSONException exc) {
        this.sequenceNrRequest = null;
        this.sequenceNrResponse = null;
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

  public Integer nr() {
    return this.nr;
  }

  public Integer sequenceNrRequest() {
    return this.sequenceNrRequest;
  }

  public Integer sequenceNrResponse() {
    return this.sequenceNrResponse;
  }

  public void setNr(int nr) {
    this.nr = nr;
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

  public void setSequenceNrRequest(int n) {
    try {
      var jsonObj = new JSONObject(this.request.bodyToString());
      jsonObj.put("#", n);
      this.request = this.request.withBody(jsonObj.toString());
    } catch (JSONException exc) {
      // ignore
    }
  }

  public EclipseScoutExchange withUiSessionId(String uiSessionId) {
    this.setUiSessionId(uiSessionId);
    return this;
  }

  public EclipseScoutExchange withSequenceNrRequest(int n) {
    this.setSequenceNrRequest(n);
    return this;
  }

  public String getUiSessionId() {
    return this.uiSessionId;
  }

  public EclipseScoutExchange clone() {
    HttpRequest clonedRequest = HttpRequest.httpRequest(this.request.httpService(), this.request.toByteArray());
    HttpResponse clonedResponse = null;
    if (this.response != null) {
      clonedResponse = HttpResponse.httpResponse(this.response.toByteArray());
    }
    return new EclipseScoutExchange(HttpRequestResponse.httpRequestResponse(clonedRequest, clonedResponse));
  }

  public void send() {
    var response = EclipseScoutExtension.api.http().sendRequest(this.request).response();
    this.setResponse(response);
  }

}
