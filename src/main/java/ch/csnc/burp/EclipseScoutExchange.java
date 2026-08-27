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
import burp.api.montoya.http.message.MimeType;
import burp.api.montoya.http.message.requests.HttpRequest;
import burp.api.montoya.http.message.responses.HttpResponse;

public class EclipseScoutExchange {

  /**
   * Tags a request with this exchange's {@link #nr()}, so {@link EclipseScoutExtension} can
   * recognize and replay it as part of a sequence no matter how it ends up sent to
   * Repeater/Intruder - via this extension's own "Send to Repeater"/"Send to Intruder" actions,
   * or via Burp's own built-in editor shortcut/context-menu action, which extension code can't
   * hook into and would otherwise send the request untagged. Always stripped again before the
   * request actually goes out over the wire (see
   * {@code EclipseScoutExtension.handleHttpRequestToBeSent}), and deliberately left off when
   * sending to Organizer (see {@code EclipseScoutExtension}'s "Send to Organizer" action)
   * since those entries are meant as plain reference copies, not replay-sequence members.
   */
  static final String REQUEST_HEADER = "X-Scout-Request";

  /**
   * Tags a request sent to Repeater/Intruder with the {@link #nr()} of a different row, telling
   * {@code EclipseScoutExtension.handleHttpResponseReceived} to show that row's response back in the
   * Repeater/Intruder tab once the sequence has replayed far enough to reach it, instead of the
   * response to the request actually sent there. Like {@link #REQUEST_HEADER}, always stripped
   * before the request goes out over the wire.
   */
  static final String RESPONSE_HEADER = "X-Scout-Response";

  /**
   * Tags a request with the name of the {@code EclipseScoutSequenceTab} it belongs to, so
   * {@code EclipseScoutExtension.handleHttpRequestToBeSent} knows which sequence's rows/variables
   * to replay against when the request comes back tagged with {@link #REQUEST_HEADER} - needed
   * now that more than one sequence can exist, so a bare "#" is no longer enough to find the row.
   * Like {@link #REQUEST_HEADER}, always stripped again before the request actually goes out over
   * the wire.
   */
  static final String SEQUENCE_HEADER = "X-Scout-Sequence";

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

  /** Decodes {@code message}'s body using its own declared charset, per {@link #jsonCharset}. */
  private static String jsonBody(HttpMessage message) {
    return new String(message.body().getBytes(), jsonCharset(message));
  }

  /** Serializes {@code jsonObj} using the charset {@code originalMessage} declared for its own body. */
  private static ByteArray jsonBody(HttpMessage originalMessage, JSONObject jsonObj) {
    return ByteArray.byteArray(jsonObj.toString().getBytes(jsonCharset(originalMessage)));
  }

  /** Encodes {@code text} using the charset {@code originalMessage} declared for its own body. */
  private static ByteArray jsonBody(HttpMessage originalMessage, String text) {
    return ByteArray.byteArray(text.getBytes(jsonCharset(originalMessage)));
  }

  /** Wraps a request with no response yet, e.g. one about to be sent for the first time. */
  public EclipseScoutExchange(HttpRequest request) {
    this(HttpRequestResponse.httpRequestResponse(request, null));
  }

  /**
   * Wraps a defensive copy of {@code requestResponse}'s request/response, extracting the "#"
   * sequence number from each body's top-level JSON object, if present, into
   * {@link #sequenceNrRequest()}/{@link #sequenceNrResponse()} for display.
   */
  public EclipseScoutExchange(HttpRequestResponse requestResponse) {
    this.request = HttpRequest.httpRequest(requestResponse.request().httpService(),
        requestResponse.request().toByteArray());
    if (requestResponse.response() != null) {
      this.response = HttpResponse.httpResponse(requestResponse.response().toByteArray());
    }

    // Checked independently per message - a request/response pair's Content-Type isn't
    // symmetric (e.g. a plain GET with no body can still get a JSON response), so gating the
    // response-side extraction on the request's Content-Type would silently miss it.
    if (this.request.contentType() == ContentType.JSON) {
      try {
        var body = jsonBody(this.request).strip();
        if (body.startsWith("{")) {
          var jsonObj = new JSONObject(body);
          this.sequenceNrRequest = jsonObj.has("#") ? jsonObj.getInt("#") : null;
        }
      } catch (JSONException exc) {
        this.sequenceNrRequest = null;
      }
    }
    if (this.response != null && this.response.mimeType() == MimeType.JSON) {
      try {
        var body = jsonBody(this.response).strip();
        if (body.startsWith("{")) {
          var jsonObj = new JSONObject(body);
          this.sequenceNrResponse = jsonObj.has("#") ? jsonObj.getInt("#") : null;
        }
      } catch (JSONException exc) {
        this.sequenceNrResponse = null;
      }
    }
  }

  /** This exchange's request, as most recently captured, edited, or remapped/renumbered for replay. */
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

  /** This exchange's response, or {@code null} if it hasn't been sent (or replayed) yet. */
  public HttpResponse response() {
    return this.response;
  }

  /**
   * Whether {@code response} carries a Scout "startupData" object - present only on the response
   * to the very first request of a new UI session (see {@link #setResponse}) - or {@code false}
   * if it doesn't, isn't JSON, or is {@code null}.
   */
  public static boolean isStartupResponse(HttpResponse response) {
    if (response == null) {
      return false;
    }
    try {
      return new JSONObject(jsonBody(response)).has("startupData");
    } catch (JSONException exc) {
      return false;
    }
  }

  /**
   * Records {@code response} as this exchange's outcome and, if it carries a Scout "startupData"
   * object, refreshes {@link #getUiSessionId()} from it - Scout issues a new UI session id on
   * whichever response first establishes one, which every later request in that session must echo.
   */
  public void setResponse(HttpResponse response) {
    this.response = response;
    if (response == null) {
      // e.g. the request failed at the network level (connection refused/timeout/host down) and
      // Burp couldn't produce a response - nothing to extract a uiSessionId from.
      return;
    }
    try {
      var jsonObj = new JSONObject(jsonBody(response));
      var startupData = jsonObj.getJSONObject("startupData");
      this.uiSessionId = startupData.getString("uiSessionId");
    } catch (JSONException exc) {
      // ignore
    }
  }

  /** This exchange's request and response paired together, e.g. for display in Burp's own UI. */
  public HttpRequestResponse requestResponse() {
    return HttpRequestResponse.httpRequestResponse(this.request, this.response);
  }

  /** This exchange's static "#" - its fixed row identity, independent of display order or replay. */
  public Integer nr() {
    return this.nr;
  }

  /** The "#" sequence number found in the request body at capture time, for display only. */
  public Integer sequenceNrRequest() {
    return this.sequenceNrRequest;
  }

  /** The "#" sequence number found in the response body at capture time, for display only. */
  public Integer sequenceNrResponse() {
    return this.sequenceNrResponse;
  }

  /** Whether this exchange's request is one of Scout's long-polling requests, e.g. {@code /json?poll}. */
  public boolean isPollRequest() {
    return this.request.path().contains("?poll");
  }

  /**
   * Whether this exchange's response body is JSON containing nothing but its "#" - e.g.
   * {@code {"#":28}} - the shape of an empty {@link #isPollRequest} response, one that reported
   * no events.
   */
  public boolean hasOnlyResponseNr() {
    if (this.response == null) {
      return false;
    }
    try {
      var body = jsonBody(this.response).strip();
      if (!body.startsWith("{")) {
        return false;
      }
      var jsonObj = new JSONObject(body);
      return jsonObj.keySet().size() == 1 && jsonObj.has("#");
    } catch (JSONException exc) {
      return false;
    }
  }

  /**
   * Whether this exchange's response body is a JSON object with no keys at all - {@code {}},
   * not an empty body - another shape of an empty {@link #isPollRequest} response, one that
   * didn't even echo back its "#".
   */
  public boolean hasEmptyResponseObject() {
    if (this.response == null) {
      return false;
    }
    try {
      var body = jsonBody(this.response).strip();
      if (!body.startsWith("{")) {
        return false;
      }
      return new JSONObject(body).keySet().isEmpty();
    } catch (JSONException exc) {
      return false;
    }
  }

  /** Assigns this exchange's static "#", tagging its request with {@link #REQUEST_HEADER} so it stays recognizable. */
  public void setNr(int nr) {
    this.nr = nr;
    this.request = this.request.withHeader(REQUEST_HEADER, String.valueOf(nr));
  }

  /** Tags this exchange's request with the name of the sequence it belongs to - see {@link #SEQUENCE_HEADER}. */
  public void setSequenceName(String sequenceName) {
    this.request = this.request.withHeader(SEQUENCE_HEADER, sequenceName);
  }

  /**
   * Adds or updates (if already present) a header on this exchange's request - e.g. to refresh a
   * stale Cookie or Authorization header across an entire captured sequence at once.
   */
  public void setHeader(String name, String value) {
    this.request = this.request.withHeader(name, value);
  }

  /**
   * Removes a header from this exchange's request, if present. A no-op if it isn't.
   */
  public void removeHeader(String name) {
    this.request = this.request.withRemovedHeader(name);
  }

  /**
   * Propagates {@code uiSessionId} onto this exchange's request body, so it targets the UI
   * session that was actually established during this replay run rather than whatever id was
   * originally captured.
   */
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

  /** Recursively walks {@code node} for "nodes" arrays, delegating each to {@link #collectNodeClassIdsFromDefinitions}. */
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

  /** Records each node definition's id-to-classId pair in {@code nodesArray}, recursing into "childNodes". */
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

  /** Recursively walks {@code node} for "rows" arrays, recording each row's id-to-nodeId pair. */
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
   * Maps each table's target id to the ordered list of new row ids introduced in this exchange's
   * response body via "rowsInserted" events on that table - e.g. the row created by an "Add row"
   * action. Unlike a row that mirrors a tree node (see {@link #responseRowNodeIds()}), a row
   * like this has neither a classId of its own nor a nodeId to resolve through - only its owning
   * table and the order it appeared in, which is what {@link EclipseScoutSequence} matches on.
   */
  public Map<String, List<String>> responseInsertedRowIds() {
    if (this.response == null) {
      return Map.of();
    }
    try {
      var body = jsonBody(this.response).strip();
      if (!body.startsWith("{")) {
        return Map.of();
      }
      var jsonObj = new JSONObject(body);
      var result = new HashMap<String, List<String>>();
      collectInsertedRowIds(jsonObj, result);
      return result;
    } catch (JSONException exc) {
      return Map.of();
    }
  }

  /** Recursively walks {@code node} for "rowsInserted" events, appending each one's row ids under its "target". */
  private static void collectInsertedRowIds(Object node, Map<String, List<String>> result) {
    if (node instanceof JSONObject obj) {
      if ("rowsInserted".equals(obj.opt("type")) && obj.has("target") && obj.has("rows")) {
        var rowIds = result.computeIfAbsent(obj.getString("target"), key -> new ArrayList<>());
        var rows = obj.getJSONArray("rows");
        for (int i = 0; i < rows.length(); i++) {
          var rowObj = rows.getJSONObject(i);
          if (rowObj.has("id")) {
            rowIds.add(rowObj.getString("id"));
          }
        }
      }
      for (var key : obj.keySet()) {
        collectInsertedRowIds(obj.get(key), result);
      }
    } else if (node instanceof JSONArray arr) {
      for (int i = 0; i < arr.length(); i++) {
        collectInsertedRowIds(arr.get(i), result);
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

  /** Replaces every {@code $VAR:name$} occurrence in {@code text} with its current value from {@code variableValues}. */
  private static String substitutePlaceholders(String text, Map<String, String> variableValues) {
    var updated = text;
    for (var entry : variableValues.entrySet()) {
      if (entry.getValue() != null) {
        updated = updated.replace("$VAR:" + entry.getKey() + "$", entry.getValue());
      }
    }
    return updated;
  }

  /** Recursively walks {@code node}, rewriting each "target"/"nodeId"/"rowId"/"nodeIds"/"rowIds" key found. */
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

  /** Overwrites {@code obj}'s {@code key} with {@code resolver}'s result, unless it returns {@code null}. */
  private static void applyIfResolved(JSONObject obj, String key, String originalValue,
      Function<String, String> resolver) {
    var replacement = resolver.apply(originalValue);
    if (replacement != null) {
      obj.put(key, replacement);
    }
  }

  /** Replaces each string element of {@code arr} with {@code resolver}'s result, leaving unresolved ones untouched. */
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

  /** Fluent form of {@link #setUiSessionId}, returning this exchange for chaining. */
  public EclipseScoutExchange withUiSessionId(String uiSessionId) {
    this.setUiSessionId(uiSessionId);
    return this;
  }

  /** The UI session id last observed via {@link #setResponse} or set via {@link #setUiSessionId}. */
  public String getUiSessionId() {
    return this.uiSessionId;
  }

  /** Deep-copies this exchange's request/response and static "#", so a replay run can mutate its own copy freely. */
  public EclipseScoutExchange clone() {
    var clonedRequest = HttpRequest.httpRequest(this.request.httpService(), this.request.toByteArray());
    HttpResponse clonedResponse = null;
    if (this.response != null) {
      clonedResponse = HttpResponse.httpResponse(this.response.toByteArray());
    }
    var cloned = new EclipseScoutExchange(HttpRequestResponse.httpRequestResponse(clonedRequest, clonedResponse));
    cloned.nr = this.nr;
    return cloned;
  }

  /**
   * Sends this exchange's request directly (not via Repeater/Intruder). {@link #REQUEST_HEADER}
   * and {@link #SEQUENCE_HEADER} are stripped first: {@code api.http().sendRequest()} is
   * processed by Burp like a request sent by any tool, including this extension's own registered
   * {@code HttpHandler} - leaving {@link #REQUEST_HEADER} on would make every programmatic send
   * (e.g. each earlier step {@link EclipseScoutSequence} replays before reaching the row actually
   * requested) re-enter that handler's sequence-replay branch instead of just going out over the
   * wire; {@link #SEQUENCE_HEADER} is only ever meaningful alongside it, so it's stripped too.
   */
  public void send() {
    var response = EclipseScoutExtension.api.http()
        .sendRequest(this.request.withRemovedHeader(REQUEST_HEADER).withRemovedHeader(SEQUENCE_HEADER))
        .response();
    this.setResponse(response);
  }

}
