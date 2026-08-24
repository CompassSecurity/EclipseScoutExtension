package ch.csnc.burp;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import burp.api.montoya.core.ByteArray;
import burp.api.montoya.http.message.ContentType;
import burp.api.montoya.http.message.HttpMessage;
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

  private static final Pattern CHARSET_PATTERN =
      Pattern.compile("charset\\s*=\\s*\"?([^;\"\\s]+)", Pattern.CASE_INSENSITIVE);

  /**
   * {@link HttpMessage#bodyToString()} decodes body bytes using a fixed internal charset that
   * ignores both the message's own "Content-Type" header and Burp's "message editor character
   * sets" display setting, while {@link HttpRequest#withBody(String)} encodes using UTF-8 - an
   * asymmetry that silently mangles non-ASCII characters (e.g. curly quotes) on any body that
   * gets read then written back. Scout's UI protocol always declares "charset=UTF-8" anyway, so
   * bodies are read/written here as raw bytes, decoded/encoded explicitly using the charset from
   * the message's Content-Type header (defaulting to UTF-8 if absent), bypassing bodyToString()/
   * withBody(String) entirely.
   */
  private static Charset jsonCharset(HttpMessage message) {
    var contentType = message.header("Content-Type");
    if (contentType != null) {
      var matcher = CHARSET_PATTERN.matcher(contentType.value());
      if (matcher.find()) {
        try {
          return Charset.forName(matcher.group(1));
        } catch (Exception exc) {
          // unrecognized charset name, fall back to UTF-8 below
        }
      }
    }
    return StandardCharsets.UTF_8;
  }

  private static String jsonBody(HttpMessage message) {
    return new String(message.body().getBytes(), jsonCharset(message));
  }

  private static ByteArray jsonBody(HttpMessage originalMessage, JSONObject jsonObj) {
    return ByteArray.byteArray(jsonObj.toString().getBytes(jsonCharset(originalMessage)));
  }

  private static ByteArray jsonBody(HttpMessage originalMessage, String text) {
    return ByteArray.byteArray(text.getBytes(jsonCharset(originalMessage)));
  }

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
          var body = jsonBody(this.request).strip();
          if (body.startsWith("{")) {
            var jsonObj = new JSONObject(body);
            this.sequenceNrRequest = jsonObj.has("#") ? jsonObj.getInt("#") : null;
          }
        }
        {
          if (this.response != null) {
            var body = jsonBody(this.response).strip();
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

  /**
   * Replaces this exchange's request outright, e.g. with one just edited live in Repeater's or
   * Intruder's request pane, so that {@link EclipseScoutSequence#next()}'s pipeline (uiSessionId
   * propagation, sequence renumbering, id remapping, variable substitution) applies on top of the
   * edit instead of the originally captured body.
   */
  public void overrideRequest(HttpRequest request) {
    this.request = request;
  }

  public HttpResponse response() {
    return this.response;
  }

  public void setResponse(HttpResponse response) {
    this.response = response;
    try {
      var jsonObj = new JSONObject(jsonBody(response));
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
      var jsonObj = new JSONObject(jsonBody(this.request));
      jsonObj.put("uiSessionId", uiSessionId);
      this.request = this.request.withBody(jsonBody(this.request, jsonObj));
    } catch (JSONException exc) {
      // ignore
    }
  }

  /**
   * Overwrites the "#" sequence number in the request body with {@code n}. Used only when
   * an exchange is auto-replayed as part of a sequence, to keep the body's sequence number
   * contiguous. This never touches {@link #sequenceNrRequest()}, which stays whatever was
   * originally captured in the body for display purposes.
   */
  public void renumberSequenceNr(int n) {
    try {
      var jsonObj = new JSONObject(jsonBody(this.request));
      jsonObj.put("#", n);
      this.request = this.request.withBody(jsonBody(this.request, jsonObj));
    } catch (JSONException exc) {
      // ignore
    }
  }

  /**
   * Maps each id introduced in this exchange's response body's "adapterData" to that
   * adapter's "classId" - a session-independent id Scout derives from the widget's static
   * position in the UI definition, so the same logical widget (e.g. "the desktop's Outline")
   * gets the same classId across different sessions/replays even though its numeric adapter
   * id differs each time.
   */
  public Map<String, String> responseAdapterClassIds() {
    if (this.response == null) {
      return Map.of();
    }
    try {
      var body = jsonBody(this.response).strip();
      if (!body.startsWith("{")) {
        return Map.of();
      }
      var jsonObj = new JSONObject(body);
      if (!jsonObj.has("adapterData")) {
        return Map.of();
      }
      var adapterData = jsonObj.getJSONObject("adapterData");
      var result = new HashMap<String, String>();
      for (var id : adapterData.keySet()) {
        var adapterObj = adapterData.getJSONObject(id);
        if (adapterObj.has("classId")) {
          result.put(id, adapterObj.getString("classId"));
        }
      }
      return result;
    } catch (JSONException exc) {
      return Map.of();
    }
  }

  /**
   * Maps each id introduced in this exchange's response body's tree/outline node definitions
   * (found under a "nodes" key, recursing into "childNodes") to that node's "classId" - like
   * {@link #responseAdapterClassIds()}, but for tree nodes, which carry their own classId
   * distinct from their parent Outline/Tree adapter's.
   */
  public Map<String, String> responseNodeClassIds() {
    if (this.response == null) {
      return Map.of();
    }
    try {
      var body = jsonBody(this.response).strip();
      if (!body.startsWith("{")) {
        return Map.of();
      }
      var jsonObj = new JSONObject(body);
      var result = new HashMap<String, String>();
      collectNodeClassIds(jsonObj, result);
      return result;
    } catch (JSONException exc) {
      return Map.of();
    }
  }

  private static void collectNodeClassIds(Object node, Map<String, String> result) {
    if (node instanceof JSONObject obj) {
      for (var key : obj.keySet()) {
        var value = obj.get(key);
        if (key.equals("nodes") && value instanceof JSONArray arr) {
          collectNodeClassIdsFromDefinitions(arr, result);
        } else {
          collectNodeClassIds(value, result);
        }
      }
    } else if (node instanceof JSONArray arr) {
      for (int i = 0; i < arr.length(); i++) {
        collectNodeClassIds(arr.get(i), result);
      }
    }
  }

  private static void collectNodeClassIdsFromDefinitions(JSONArray nodesArray, Map<String, String> result) {
    for (int i = 0; i < nodesArray.length(); i++) {
      var nodeObj = nodesArray.getJSONObject(i);
      if (nodeObj.has("id") && nodeObj.has("classId")) {
        result.put(nodeObj.getString("id"), nodeObj.getString("classId"));
      }
      if (nodeObj.has("childNodes")) {
        collectNodeClassIdsFromDefinitions(nodeObj.getJSONArray("childNodes"), result);
      }
    }
  }

  /**
   * Maps each table row id introduced in this exchange's response body (found under a "rows"
   * key) to that row's "nodeId", for rows that carry one - the "detail table" rows Scout uses
   * to mirror an Outline's node tree as a table. Rows themselves have no classId of their own;
   * this lets {@link EclipseScoutSequence} resolve a row's identity indirectly through the
   * node it mirrors.
   */
  public Map<String, String> responseRowNodeIds() {
    if (this.response == null) {
      return Map.of();
    }
    try {
      var body = jsonBody(this.response).strip();
      if (!body.startsWith("{")) {
        return Map.of();
      }
      var jsonObj = new JSONObject(body);
      var result = new HashMap<String, String>();
      collectRowNodeIds(jsonObj, result);
      return result;
    } catch (JSONException exc) {
      return Map.of();
    }
  }

  private static void collectRowNodeIds(Object node, Map<String, String> result) {
    if (node instanceof JSONObject obj) {
      for (var key : obj.keySet()) {
        var value = obj.get(key);
        if (key.equals("rows") && value instanceof JSONArray arr) {
          for (int i = 0; i < arr.length(); i++) {
            var rowObj = arr.getJSONObject(i);
            if (rowObj.has("id") && rowObj.has("nodeId")) {
              result.put(rowObj.getString("id"), rowObj.getString("nodeId"));
            }
          }
        } else {
          collectRowNodeIds(value, result);
        }
      }
    } else if (node instanceof JSONArray arr) {
      for (int i = 0; i < arr.length(); i++) {
        collectRowNodeIds(arr.get(i), result);
      }
    }
  }

  /**
   * Rewrites "target" (adapter), "nodeId"/"nodeIds" (tree node) and "rowId"/"rowIds" (table
   * row) ids referenced in this exchange's request body, each through its own resolver -
   * returning {@code null} from a resolver leaves that particular id untouched. See
   * {@link EclipseScoutSequence} for how each resolver derives a replacement via
   * classId-based identity matching against the current replay run.
   */
  public void remapIds(Function<String, String> targetResolver, Function<String, String> nodeIdResolver,
      Function<String, String> rowIdResolver) {
    try {
      var jsonObj = new JSONObject(jsonBody(this.request));
      remapIdsRecursive(jsonObj, targetResolver, nodeIdResolver, rowIdResolver);
      this.request = this.request.withBody(jsonBody(this.request, jsonObj));
    } catch (JSONException exc) {
      // ignore
    }
  }

  /**
   * The response body as text, decoded per {@link #jsonCharset(HttpMessage)}, or {@code null}
   * if this exchange has no response yet. Used both for live variable-regex preview in the UI
   * and for extraction during replay.
   */
  public String responseBodyText() {
    return this.response != null ? jsonBody(this.response) : null;
  }

  /**
   * Runs the regex of each variable declared against this specific exchange (matched by
   * {@link #nr()} against {@link EclipseScoutVariable#exchangeNr()}) on this exchange's response
   * body, returning the values of those that matched (capture group 1 if the regex has one,
   * otherwise the whole match). A variable targeting a different exchange, or with a blank
   * name/regex, or whose regex doesn't match, is omitted from the result.
   */
  public Map<String, String> extractVariables(List<EclipseScoutVariable> variables) {
    var result = new HashMap<String, String>();
    var body = this.responseBodyText();
    if (body == null || this.nr == null) {
      return result;
    }
    for (var variable : variables) {
      if (!this.nr.equals(variable.exchangeNr())) {
        continue;
      }
      if (variable.name() == null || variable.name().isBlank() || variable.regex() == null
          || variable.regex().isBlank()) {
        continue;
      }
      try {
        var matcher = Pattern.compile(variable.regex()).matcher(body);
        if (matcher.find()) {
          result.put(variable.name(), matcher.groupCount() >= 1 ? matcher.group(1) : matcher.group(0));
        }
      } catch (PatternSyntaxException exc) {
        // ignore invalid regex
      }
    }
    return result;
  }

  /**
   * Replaces every {@code $VAR:name$} placeholder found in this exchange's request path, header
   * values, or body with the current value of the variable named {@code name}, for each entry in
   * {@code variableValues}. A variable with no current value (not yet extracted from an earlier
   * response) is left as-is.
   *
   * <p>The body is only rewritten if decoding it with its declared charset and substituting
   * actually changes something - so a non-text body (e.g. a multipart file upload's binary part)
   * is left byte-for-byte untouched rather than round-tripped through a text charset, which for
   * arbitrary binary content would be lossy.
   */
  public void substituteVariables(Map<String, String> variableValues) {
    var path = this.request.path();
    var updatedPath = substitutePlaceholders(path, variableValues);
    if (!updatedPath.equals(path)) {
      this.request = this.request.withPath(updatedPath);
    }
    var body = jsonBody(this.request);
    var updatedBody = substitutePlaceholders(body, variableValues);
    if (!updatedBody.equals(body)) {
      this.request = this.request.withBody(jsonBody(this.request, updatedBody));
    }
    for (var header : new ArrayList<>(this.request.headers())) {
      var updatedValue = substitutePlaceholders(header.value(), variableValues);
      if (!updatedValue.equals(header.value())) {
        this.request = this.request.withUpdatedHeader(header.name(), updatedValue);
      }
    }
  }

  private static String substitutePlaceholders(String text, Map<String, String> variableValues) {
    var updated = text;
    for (var entry : variableValues.entrySet()) {
      if (entry.getValue() != null) {
        updated = updated.replace("$VAR:" + entry.getKey() + "$", entry.getValue());
      }
    }
    return updated;
  }

  private static void remapIdsRecursive(Object node, Function<String, String> targetResolver,
      Function<String, String> nodeIdResolver, Function<String, String> rowIdResolver) {
    if (node instanceof JSONObject obj) {
      for (var key : new ArrayList<>(obj.keySet())) {
        var value = obj.get(key);
        if (key.equals("target") && value instanceof String str) {
          applyIfResolved(obj, key, str, targetResolver);
        } else if (key.equals("nodeId") && value instanceof String str) {
          applyIfResolved(obj, key, str, nodeIdResolver);
        } else if (key.equals("rowId") && value instanceof String str) {
          applyIfResolved(obj, key, str, rowIdResolver);
        } else if (key.equals("nodeIds") && value instanceof JSONArray arr) {
          remapArray(arr, nodeIdResolver);
        } else if (key.equals("rowIds") && value instanceof JSONArray arr) {
          remapArray(arr, rowIdResolver);
        } else {
          remapIdsRecursive(value, targetResolver, nodeIdResolver, rowIdResolver);
        }
      }
    } else if (node instanceof JSONArray arr) {
      for (int i = 0; i < arr.length(); i++) {
        remapIdsRecursive(arr.get(i), targetResolver, nodeIdResolver, rowIdResolver);
      }
    }
  }

  private static void applyIfResolved(JSONObject obj, String key, String originalValue,
      Function<String, String> resolver) {
    var replacement = resolver.apply(originalValue);
    if (replacement != null) {
      obj.put(key, replacement);
    }
  }

  private static void remapArray(JSONArray arr, Function<String, String> resolver) {
    for (int i = 0; i < arr.length(); i++) {
      if (arr.get(i) instanceof String str) {
        var replacement = resolver.apply(str);
        if (replacement != null) {
          arr.put(i, replacement);
        }
      }
    }
  }

  public EclipseScoutExchange withUiSessionId(String uiSessionId) {
    this.setUiSessionId(uiSessionId);
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
    var cloned = new EclipseScoutExchange(HttpRequestResponse.httpRequestResponse(clonedRequest, clonedResponse));
    cloned.nr = this.nr;
    return cloned;
  }

  public void send() {
    var response = EclipseScoutExtension.api.http().sendRequest(this.request).response();
    this.setResponse(response);
  }

}
